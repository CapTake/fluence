/*
 * Copyright (C) 2017  Fluence Labs Limited
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package fluence.btree.server

import java.nio.ByteBuffer

import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.{~>, MonadError}
import com.typesafe.config.Config
import fluence.btree.common.merkle.{GeneralNodeProof, MerklePath}
import fluence.btree.common.ValueRef
import fluence.btree.core.{ClientPutDetails, Hash, Key}
import fluence.btree.server.core.TreePath.PathElem
import fluence.btree.server.core._
import fluence.codec.kryo.KryoCodecs
import fluence.crypto.Crypto
import fluence.storage.rocksdb.{IdSeqProvider, RocksDbStore}
import monix.eval.{Task, TaskSemaphore}
import monix.execution.atomic.AtomicInt
import monix.reactive.Observable

import scala.collection.Searching.{Found, InsertionPoint, SearchResult}
import scala.language.higherKinds

/**
 * This class implements a search tree, which allows to run queries over encrypted data. This code based on research paper:
 * '''Popa R.A.,Li F.H., Zeldovich N. 'An ideal-security protocol for order-preserving encoding.' 2013 '''
 *
 * In its essence this tree is a hybrid of B+Tree [[https://en.wikipedia.org/wiki/B%2B_tree]] and MerkleTree
 * [[https://en.wikipedia.org/wiki/Merkle_tree]] data structures.
 *
 * This ''B+tree'' is an N-ary tree with a number of children per node ranging between ''MinDegree'' and ''MaxDegree''.
 * A tree consists of a root, internal branch nodes and leaves. The root may be either a leaf or a node with two or
 * more children. Copies of the some keys are stored in the internal nodes (for efficient searching); keys and
 * records are stored in leaves. Tree is kept balanced by requiring that all leaf nodes are at the same depth.
 * This depth will increase slowly as elements are added to the tree. Depth increases only when the root is being splitted.
 *
 * Note that the tree provides only algorithms (i.e., functions) to search, insert and delete elements.
 * Tree nodes are actually stored externally using the [[BTreeStore]] to make the tree
 * maximally pluggable and seamlessly switch between in memory, on disk, or maybe, over network storages. Key comparison
 * operations in the tree are also pluggable and are provided by the [[BTreeCommand]] implementations, which helps to
 * impose an order over for example encrypted nodes data.
 *
 * @param conf    Config for this tree
 * @param store   BTree persistence store for persisting tree nodes
 * @param nodeOps Operations performed on nodes
 */
class MerkleBTree private[server] (
  conf: MerkleBTreeConfig,
  store: BTreeStore[Task, NodeId, Node],
  nodeOps: NodeOps
) extends slogging.LazyLogging {

  import MerkleBTree._
  import nodeOps._

  /** max of node size */
  private val MaxDegree = conf.arity

  /** min of node size except root */
  private val MinDegree = conf.alpha * conf.arity

  /* number of tree levels */
  private val depth = AtomicInt(0)
  /* mutex for single-thread access to a tree */
  private val globalMutex = TaskSemaphore(1).memoize

  /* Public methods */

  /**
   * === GET ===
   *
   * We are looking for a specified key in this B+Tree.
   * Starting from the root, we are looking for some leaf which needed to the BTree client. We using [[Get]]
   * for communication with client. At each node, we figure out which internal pointer we should follow.
   * Get have O(log,,arity,,n) algorithmic complexity.
   *
   * @param cmd A command for BTree execution (it's a 'bridge' for communicate with BTree client)
   * @return reference to value that corresponds search Key, or None if Key was not found in tree
   */
  def get(cmd: Get): Task[Option[ValueRef]] = {
    globalMutex.flatMap(_.greenLight(getRoot.flatMap(root ⇒ getForRoot(root, cmd))))
  }

  /**
   * === Range ===
   *
   * We are looking for a starting key of range in this B+Tree.
   * Starting from the root, we are looking for some leaf which needed to the BTree client. We using [[Range]]
   * for communication with client. At each node, we figure out which internal pointer we should follow.
   * When we found specified search key in a leaf, we should be returning all key-value pairs from searched position
   * until the stream won't stopped by consumer
   * Range have O(log,,arity,,n+k) algorithmic complexity, where ''k'' is number of returned pairs.
   *
   * @param cmd A command for BTree execution (it's a 'bridge' for communicate with BTree client)
   * @return stream of references to values that corresponds search command, or empty if nothing was found
   */
  def range(cmd: Range): Observable[(Key, ValueRef)] = {
    Observable
      .fromTask(globalMutex.flatMap(_.greenLight(getRoot)))
      .flatMap(mRoot ⇒ rangeForRoot(mRoot, cmd))
  }

  /**
   * === PUT ===
   *
   * Starting from the root, we are looking for some place for putting key and value in this B+Tree.
   * We using [[Get]] for communication with client. At each node, we figure out which internal pointer we
   * should follow. When we go down the tree we put each visited node to ''Trail''(see [[TreePath]]) in the same
   * order. Trail is just a array of all visited nodes from root to leaf. When we found slot for insertion we do all
   * tree transformation in logical copy of sector of tree; actually ''Trail'' is this copy - copy of visited nodes
   * that will be changed after insert. We insert new ''key'' and ''value'' and split leaf if leaf is full and we
   * split leaf parent if parent is filled and so on to the root. Also after changing leaf we should re-calculate
   * merkle root and update checksums of all visited nodes. Absolutely all tree transformations are performed on
   * copies and do not change the tree. When all transformation in logical state ended we commit changes (see method
   * 'commitNewState').
   * Put have O(log,,arity,,n) algorithmic complexity.
   *
   * @param cmd A command for BTree execution (it's a 'bridge' for communicate with BTree client)
   * @return reference to value that corresponds search Key. In update case will be returned old reference,
   *          in insert case will be created new reference to value
   */
  def put(cmd: Put): Task[ValueRef] = {
    globalMutex.flatMap(_.greenLight(getRoot.flatMap(root ⇒ putForRoot(root, cmd))))
  }

  def getDepth: Int = depth.get // todo remove depth or move to root node

  def getMerkleRoot: Task[Hash] = getRoot.map(_.checksum) // todo add merkle root "min" and "max" boundaries

  /* Private methods */

  private def isEmpty(node: Node): Boolean = node.size == 0

  private def hasOverflow(node: Node): Boolean = node.size > MaxDegree

  private[btree] def getRoot: Task[Node] = {
    store.get(RootId).attempt.map(_.toOption).flatMap {
      case Some(node) ⇒
        Task(node)
      case None ⇒
        val emptyLeaf = nodeOps.createEmptyLeaf
        commitNewState(PutTask(Seq(NodeWithId(RootId, emptyLeaf))))
          .map(_ ⇒ emptyLeaf)
    }
  }

  /* GET */

  /** Entry point for any Get operations. */
  private def getForRoot(root: Node, cmd: Get): Task[Option[ValueRef]] = {
    logger.debug(s"Get starts")
    getForNode(root, cmd)
  }

  private def getForNode(root: Node, cmd: Get): Task[Option[ValueRef]] = {
    if (isEmpty(root)) {
      return Task(None) // This is the terminal action, nothing to find in empty tree
    }

    root match {
      case leaf: Leaf @unchecked ⇒
        getForLeaf(leaf, cmd)
      case branch: Branch @unchecked ⇒
        getForBranch(branch, cmd)
    }
  }

  /** '''Method makes remote call!''' This method makes step down the tree. */
  private def getForBranch(branch: Branch, cmd: Get): Task[Option[ValueRef]] = {
    logger.debug(s"Get for branch=$branch")

    searchChild(branch, cmd).flatMap {
      case (_, child) ⇒
        getForNode(child, cmd)
    }
  }

  /** '''Method makes remote call!'''. This is the terminal method. */
  private def getForLeaf(leaf: Leaf, cmd: Get): Task[Option[ValueRef]] = {
    logger.debug(s"Get for leaf=$leaf")
    cmd.submitLeaf(Some(leaf)).map {
      case Found(idx) ⇒ Some(leaf.valuesReferences(idx))
      case _ ⇒ None
    } // get value ref from leaf by searched index
  }

  /* RANGE */

  private def rangeForRoot(root: Node, cmd: Get): Observable[(Key, ValueRef)] = {
    logger.debug(s"Range starts")
    rangeForNode(root, cmd)
  }

  private def rangeForNode(root: Node, cmd: Get): Observable[(Key, ValueRef)] = {
    if (isEmpty(root)) {
      return Observable.empty // This is the terminal action, nothing to find in empty tree
    }

    root match {
      case leaf: Leaf @unchecked ⇒
        rangeForLeaf(leaf, cmd)
      case branch: Branch @unchecked ⇒
        rangeForBranch(branch, cmd)
    }
  }

  /** '''Method makes remote call!''' This method makes step down the tree. */
  private def rangeForBranch(branch: Branch, cmd: Get): Observable[(Key, ValueRef)] = {
    logger.debug(s"Range for branch=$branch")

    Observable.fromTask(searchChild(branch, cmd)).flatMap { case (_, child) ⇒ rangeForNode(child, cmd) }
  }

  /** '''Method makes remote call!'''. This is the terminal method. */
  private def rangeForLeaf(leaf: Leaf, cmd: Get): Observable[(Key, ValueRef)] = {
    logger.debug(s"Range for leaf=$leaf")

    Observable
      .fromTask(cmd.submitLeaf(Some(leaf)))
      .flatMap(searchResult ⇒ fetchPairs(Task(Some(leaf)), searchResult.insertionPoint))
  }

  /**
   * Fetches all key-value pairs from current leaf and all leafs from right (right siblings).
   * Starts at starting index of current leaf, ends when client close this stream
   * or is fetched last element from the last (rightmost) leaf.
   */
  private def fetchPairs(currentLeaf: Task[Option[Leaf]], idxToStartWith: Int = 0): Observable[(Key, ValueRef)] =
    Observable.fromTask(currentLeaf).flatMap {
      case None ⇒
        Observable.empty
      case Some(leaf) ⇒
        val keys = leaf.keys.slice(idxToStartWith, leaf.size)
        val refs = leaf.valuesReferences.slice(idxToStartWith, leaf.size)
        logger.debug(s"Adds '${keys.length}' key-value pair(s) from leaf=$leaf to range query results ")
        Observable.fromIterable(keys zip refs) ++ fetchPairs(getNextLeaf(leaf))
    }

  /** Fetches right sibling of current leaf */
  private def getNextLeaf(leaf: Leaf): Task[Option[Leaf]] =
    Task(leaf.rightSibling).flatMap {
      case Some(rightSinRef) ⇒
        logger.debug(s"Try to fetch right sibling for leaf=$leaf")
        store.get(rightSinRef).flatMap {
          case leaf: Leaf @unchecked ⇒
            logger.debug(s"Next leaf is $leaf")
            Task(Some(leaf))
          case node ⇒
            Task.raiseError(
              new IllegalStateException(
                s"Unexpected exception, should be fetched leaf ${classOf[Leaf]}, but actually is ${node.getClass}"
              )
            )
        }
      case None ⇒
        logger.debug(s"There is no right sibling for current leaf=$leaf")
        Task(None)
    }

  /* PUT */

  /** Entry point for any put operations. */
  private def putForRoot(root: Node, cmd: Put): Task[ValueRef] = {
    logger.debug(s"Put starts")

    // if root is empty don't need to finding slot for putting
    if (isEmpty(root)) {
      logger.debug(s"Root is empty")

      cmd.putDetails(None).flatMap {
        case BTreePutDetails(ClientPutDetails(key, valChecksum, _), valRefProvider) ⇒
          val newValRef = valRefProvider()
          val newLeaf = createLeaf(key, newValRef, valChecksum)
          // send the merkle path to the client for verification
          val leafProof = GeneralNodeProof(Hash.empty, newLeaf.kvChecksums, 0)
          cmd
            .verifyChanges(MerklePath(Seq(leafProof)), wasSplitting = false)
            .flatMap { _ ⇒
              commitNewState(PutTask(nodesToSave = Seq(NodeWithId(RootId, newLeaf)), increaseDepth = true))
            }
            .map(_ ⇒ newValRef)
      }
    } else {
      putForNode(cmd, RootId, root, TreePath.empty)
    }
  }

  private def putForNode(cmd: Put, id: NodeId, node: Node, trail: Trail): Task[ValueRef] = {
    node match {
      case leaf: Leaf @unchecked ⇒
        putForLeaf(cmd, id, leaf, trail)
      case branch: Branch @unchecked ⇒
        putForBranch(cmd, id, branch, trail)
    }
  }

  /**
   * '''Method makes remote call!'''.
   * This method finds and fetches next child, makes step down the tree and updates trail.
   *
   * @param cmd       A command for BTree execution (it's a 'bridge' for communicate with BTree client)
   * @param branchId Id of walk-through branch node
   * @param branch   Walk-through branch node
   * @param trail    The path traversed from the root
   */
  private def putForBranch(cmd: Put, branchId: NodeId, branch: Branch, trail: Trail): Task[ValueRef] = {
    logger.debug(s"Put to branch=$branch, id=$branchId")

    cmd
      .nextChildIndex(branch)
      .flatMap(searchedIdx ⇒ {
        val childId = branch.childsReferences(searchedIdx)
        store.get(childId).flatMap { child ⇒
          val newTrail = trail.addBranch(branchId, branch, searchedIdx)
          putForNode(cmd, childId, child, newTrail)
        }
      })
  }

  /**
   * '''Method makes remote call!'''.
   * Puts new ''key'' and ''value'' to this leaf.
   * Also makes all tree transformation (rebalancing, persisting to store).
   * This is the terminal method.
   *
   * @param cmd     A command for BTree execution (it's a 'bridge' for communicate with BTree client)
   * @param leafId Id of updatable leaf
   * @param leaf   Updatable Leaf
   * @param trail  The path traversed from the root
   */
  private def putForLeaf(
    cmd: Put,
    leafId: NodeId,
    leaf: Leaf,
    trail: Trail
  ): Task[ValueRef] = {
    logger.debug(s"Put to leaf=$leaf, id=$leafId")

    cmd.putDetails(Some(leaf)).flatMap { putDetails: BTreePutDetails ⇒
      val (updatedLeaf, valueRef) = updateLeaf(putDetails, leaf)
      // makes all transformations over the copy of tree
      val (newStateProof, putTask) =
        logicalPut(leafId, updatedLeaf, putDetails.clientPutDetails.searchResult.insertionPoint, trail)
      // after all the logical operations, we need to send the merkle path to the client for verification
      cmd
        .verifyChanges(newStateProof, putTask.wasSplitting)
        .flatMap { _ ⇒
          // persist all changes
          commitNewState(putTask)
        }
        .map(_ ⇒ valueRef)
    }
  }

  /**
   * This method do all mutation operations over the tree in memory without changing tree state
   * and composes merkle path for new tree state. It inserts new value to leaf, and do tree rebalancing if it needed.
   * All changes occur over copies of the visited nodes and actually don't change the tree.
   *
   * @param leafId           Id of leaf that was updated
   * @param newLeaf          Leaf that was updated with new key and value
   * @param searchedValueIdx Insertion index of a new value
   * @param trail            The path traversed from the root to a leaf with all visited tree nodes.
   * @return Tuple with [[MerklePath]] for tree after updating and [[PutTask]] for persisting changes
   */
  private def logicalPut(
    leafId: NodeId,
    newLeaf: Leaf,
    searchedValueIdx: Int,
    trail: Trail
  ): (MerklePath, PutTask) = {
    logger.debug(s"Logic put for leafId=$leafId, leaf=$newLeaf, trail=$trail")

    /**
     * Just a state for each recursive operation of ''logicalPut''.
     *
     * @param updateParentFn Function-mutator that will be applied to parent of current node
     */
    case class PutCtx(
      newStateProof: MerklePath,
      updateParentFn: PathElem[NodeId, Branch] ⇒ PathElem[NodeId, Branch] = identity,
      putTask: PutTask
    )

    /**
     * If leaf isn't overflowed
     * - updates leaf checksum into parent node and put leaf and it's parent to ''nodesToSave'' into [[PutTask]].
     *
     * If it's overflowed
     * - splits leaf into two, adds left leaf to parent as new child and update right leaf checksum into parent node.
     * - if parent ins't exist create new parent with 2 new children.
     * - puts all updated and new nodes to ''nodesToSave'' into [[PutTask]]
     *
     * @param leafId           Id of leaf that was updated
     * @param newLeaf          Leaf that was updated with new key and value
     * @param searchedValueIdx Insertion index of a new value
     */
    def createLeafCtx(leafId: NodeId, newLeaf: Leaf, searchedValueIdx: Int): PutCtx = {

      if (hasOverflow(newLeaf)) {
        logger.debug(s"Do split for leafId=$leafId, leaf=$newLeaf")

        val isRoot = leafId == RootId
        // get ids for new nodes, right node should be with new id, because each leaf points to right sibling
        val rightId = store.nextId()
        // RootId is always linked with root node and will not changed, store left node with new id if split root
        val leftId = if (isRoot) store.nextId() else leafId

        val (left, right) = newLeaf.split(rightId)

        val isInsertToTheLeft = searchedValueIdx < left.size
        val affectedLeaf = if (isInsertToTheLeft) left else right
        val affectedLeafIdx = if (isInsertToTheLeft) searchedValueIdx else searchedValueIdx - left.size
        val merklePath = MerklePath(Array(affectedLeaf.toProof(affectedLeafIdx)))

        if (isRoot) {
          // there is no parent, root leaf was splitted
          val popUpKey = left.keys.last
          val newParent = createBranch(popUpKey, ChildRef(leftId, left.checksum), ChildRef(rightId, right.checksum))
          val affectedParentIdx = if (isInsertToTheLeft) 0 else 1

          PutCtx(
            newStateProof = MerklePath(newParent.toProof(affectedParentIdx) +: merklePath.path),
            putTask = PutTask(
              nodesToSave = Seq(NodeWithId(leftId, left), NodeWithId(rightId, right), NodeWithId(RootId, newParent)),
              increaseDepth = true, // if splitting root-leaf appears - increase depth of tree
              wasSplitting = true
            )
          )
        } else {
          // some regular leaf was splitted
          PutCtx(
            newStateProof = merklePath,
            updateParentFn =
              updateAfterChildSplitting(NodeWithId(leftId, left), NodeWithId(rightId, right), isInsertToTheLeft),
            putTask =
              PutTask(nodesToSave = Seq(NodeWithId(leftId, left), NodeWithId(rightId, right)), wasSplitting = true)
          )
        }
      } else {
        PutCtx(
          newStateProof = MerklePath(Array(newLeaf.toProof(searchedValueIdx))),
          updateParentFn = updatedAfterChildChanging(newLeaf.checksum),
          putTask = PutTask(nodesToSave = Seq(NodeWithId(leafId, newLeaf)))
        )
      }
    }

    /**
     * Note that this method returns function that used for folding all visited branches from ''trail''.
     *
     * Returned function do as follow:
     *
     * If branch isn't overflowed
     * - updates branch checksum into parent node and put branch and it's parent to ''nodesToSave'' into [[PutTask]].
     *
     * If it's overflowed
     * - splits branch into two, adds left branch to parent as new child and update right branch checksum into parent node.
     * - If parent ins't exist create new parent with 2 new children.
     * - Put all updated and new nodes to ''nodesToSave'' into [[PutTask]]
     */
    def createTreePathCtx: (PathElem[NodeId, Branch], PutCtx) ⇒ PutCtx = {
      case (visitedBranch, PutCtx(merklePath, updateParentFn, PutTask(nodesToSave, _, wasSplitting))) ⇒
        val PathElem(branchId, branch, nextChildIdx) = updateParentFn(visitedBranch)

        if (hasOverflow(branch)) {
          logger.debug(s"Do split for branchId=$branchId, branch=$branch, nextChildIdx=$nextChildIdx ")

          val isRoot = branchId == RootId
          val (left, right) = branch.split

          val leftId = store.nextId()
          // RootId is always linked with root node and will not changed, store right node with new id if split root
          val rightId = if (isRoot) store.nextId() else branchId

          val isInsertToTheLeft = nextChildIdx < left.size
          val affectedBranch = if (isInsertToTheLeft) left else right
          val affectedBranchIdx = if (isInsertToTheLeft) nextChildIdx else nextChildIdx - left.size
          val newMerklePath = MerklePath(affectedBranch.toProof(affectedBranchIdx) +: merklePath.path)

          if (isRoot) {
            // there was no parent, root node was splitting
            val popUpKey = left.keys.last
            val newParent = createBranch(popUpKey, ChildRef(leftId, left.checksum), ChildRef(rightId, right.checksum))
            val affectedNewParentIdx = if (isInsertToTheLeft) 0 else 1

            PutCtx(
              newStateProof = MerklePath(newParent.toProof(affectedNewParentIdx) +: newMerklePath.path),
              putTask = PutTask(
                nodesToSave = nodesToSave ++ Seq(
                  NodeWithId(leftId, left),
                  NodeWithId(rightId, right),
                  NodeWithId(RootId, newParent)
                ),
                increaseDepth = true, // if splitting root node appears - increase depth of the tree
                wasSplitting = true
              )
            )
          } else {
            // some regular leaf was splitting
            PutCtx(
              newStateProof = newMerklePath,
              updateParentFn =
                updateAfterChildSplitting(NodeWithId(leftId, left), NodeWithId(rightId, right), isInsertToTheLeft),
              putTask =
                PutTask(nodesToSave ++ Seq(NodeWithId(leftId, left), NodeWithId(rightId, right)), wasSplitting = true)
            )
          }
        } else {
          PutCtx(
            newStateProof = MerklePath(branch.toProof(nextChildIdx) +: merklePath.path),
            updateParentFn = updatedAfterChildChanging(branch.checksum),
            putTask = PutTask(nodesToSave :+ NodeWithId(branchId, branch), wasSplitting = wasSplitting)
          )
        }
    }

    /** Returns function that update childs checksum into parent node */
    def updatedAfterChildChanging(childChecksum: Hash): PathElem[NodeId, Branch] ⇒ PathElem[NodeId, Branch] =
      visitedBranch ⇒
        visitedBranch.copy(branch = visitedBranch.branch.updateChildChecksum(childChecksum, visitedBranch.nextChildIdx))

    /**
     * This method returns function that makes two changes into the parent node:
     *  1. It inserts left node as new child before right node.
     *  2. It updates checksum and id of changed right node.
     *
     * @param left              Left node with their id
     * @param right             Right node with their id
     * @param isInsertToTheLeft Direction of further descent. True if inserted value will be update left node, false otherwise.
     * @return Function for parent updating
     */
    def updateAfterChildSplitting(
      left: NodeAndId,
      right: NodeAndId,
      isInsertToTheLeft: Boolean
    ): PathElem[NodeId, Branch] ⇒ PathElem[NodeId, Branch] = {

      case PathElem(parentId: NodeId, parentNode: Branch, nextChildIdx) ⇒
        val popUpKey = left.node.keys.last
        logger.trace(s"Add child to parent node: insertedKey=$popUpKey, insertedChild=$left, insIdx=$nextChildIdx")
        // updates parent node with new left node. Parent already contains right node as a child (but with wrong id and checksum).
        // update right node checksum and id is needed, checksum and id of right node was changed after splitting
        val branch = parentNode
          .insertChild(popUpKey, ChildRef(left.id, left.node.checksum), nextChildIdx)
          .updateChildRef(ChildRef(right.id, right.node.checksum), nextChildIdx + 1)

        val idxOfUpdatedChild = if (isInsertToTheLeft) nextChildIdx else nextChildIdx + 1

        PathElem(parentId, branch, idxOfUpdatedChild)
    }

    val leafPutCtx: PutCtx = createLeafCtx(leafId, newLeaf, searchedValueIdx)
    val PutCtx(newStateProof, _, putTask) = trail.branches.foldRight(leafPutCtx)(createTreePathCtx)
    newStateProof → putTask
  }

  /**
   * Save all changed nodes to tree store. Apply putTask to old tree state for getting new tree state.
   *
   * @param putTask Pool of changed nodes
   */
  private def commitNewState(putTask: PutTask): Task[Unit] = {
    logger.debug(s"commitNewState for nodes=${putTask.nodesToSave}")
    // todo start transaction
    Task
      .gatherUnordered(putTask.nodesToSave.map { case NodeWithId(id, node) ⇒ saveNode(id, node) })
      .foreachL(_ ⇒ if (putTask.increaseDepth) this.depth.increment())
    // todo end transaction
  }

  /**
   * Puts new ''key'' and ''value'' to this leaf.
   * If search key was found - rewrites key and value, if key wasn't found - inserts new key and value.
   *
   * @return Updated leaf with new ''key'' and ''value''
   */
  private def updateLeaf(putDetails: BTreePutDetails, leaf: Leaf): (Leaf, ValueRef) = {
    logger.debug(s"Update leaf=$leaf, putDetails=$putDetails")

    putDetails match {
      case BTreePutDetails(ClientPutDetails(key, valChecksum, Found(idxOfUpdate)), _) ⇒
        // key was founded in this Leaf, update leaf with new value
        val oldValueRef = leaf.valuesReferences(idxOfUpdate)
        leaf.rewrite(key, oldValueRef, valChecksum, idxOfUpdate) → oldValueRef
      case BTreePutDetails(ClientPutDetails(key, valChecksum, InsertionPoint(indexOfInsert)), valRefProvider) ⇒
        // key wan't found in this Leaf, insert new value to the leaf
        val newValueRef = valRefProvider()
        leaf.insert(key, newValueRef, valChecksum, indexOfInsert) -> newValueRef
    }
  }

  /* Common methods */

  /** Save specified node to tree store or locally in case when node is root. */
  private def saveNode(nodeId: NodeId, node: Node): Task[Unit] = {
    logger.debug(s"Save node (id=$nodeId,node=$node)")
    //this assert is for debugging without cryptography
    assert(assertKeyIanAscOrder(node), s"Ascending order of keys required! Invalid node=$node)")
    store.put(nodeId, node)
  }

  /**
   * '''Method makes remote call!'''. Searches and returns next child node of tree.
   * First of all we call remote client for getting index of child.
   * After that we gets child ''nodeId'' by this index. By ''nodeId'' we fetch ''child node'' from store.
   *
   * @param branch Branch node for searching
   * @param cmd A command for BTree execution (it's a 'bridge' for communicate with BTree client)
   * @return Index of searched child and the child
   */
  private def searchChild(branch: Branch, cmd: BTreeCommand[Task, Key]): Task[(Int, Node)] = {
    cmd
      .nextChildIndex(branch)
      .flatMap(searchedIdx ⇒ {
        val childId = branch.childsReferences(searchedIdx)
        store.get(childId).map(searchedIdx → _)
      })
  }

  /**
   * This method used only with enabled assertion in tests for verifying order of keys into node.
   * For disabling this check makes {{{fluence.merkleBTree.assertions.isKeyOrderRequired=false}}} or disable assertions.
   */
  private def assertKeyIanAscOrder(node: Node): Boolean = {
    val lt: (Key, Key) ⇒ Boolean = (x, y) ⇒ ByteBuffer.wrap(x.bytes).compareTo(ByteBuffer.wrap(y.bytes)) < 0
    !conf.assertions.isKeyOrderRequired || node.keys.sliding(2).forall {
      case Array(prev, next) ⇒ lt(prev, next)
      case _ ⇒ true
    }
  }

}

object MerkleBTree {

  /* Tree root id is constant, it always points to root node. */
  private val RootId = 0L

  /**
   * Creates new instance of MerkleBTree.
   *
   * @param name          Name of this btree (will be created RockDb instance with data folder == name), should be unique
   * @param rocksFactory RocksDb factory for getting registered instance of RocksDb
   * @param hasher        Hash service uses for calculating nodes checksums.
   * @param conf          MerkleBTree config
   */
  def apply[F[_]](
    name: String,
    rocksFactory: RocksDbStore.Factory,
    hasher: Crypto.Hasher[Array[Byte], Hash],
    conf: Config
  )(implicit F: MonadError[F, Throwable], runTask: Task ~> F): F[MerkleBTree] =
    F.map2(
      defaultStore(name, rocksFactory, conf),
      MerkleBTreeConfig.read(conf)
    ) { (store, conf) ⇒
      new MerkleBTree(conf, store, NodeOps(hasher))
    }

  /**
   * Default tree store with RockDb key-value storage under the hood.
   *
   * @param storeName    Store name for persisting this btree
   * @param rocksFactory Factory for creating manageable RocksDb instance.
   * @param conf          MerkleBTree config
   */
  private def defaultStore[F[_]](
    storeName: String,
    rocksFactory: RocksDbStore.Factory,
    conf: Config
  )(implicit F: MonadError[F, Throwable], runTask: Task ~> F): F[BTreeStore[Task, Long, Node]] = {
    val codecs = KryoCodecs()
      .add[Key]
      .add[Array[Key]]
      .add[Hash]
      .add[Array[Hash]]
      .add[NodeId]
      .add[Array[NodeId]]
      .add[Int]
      .add[Node]
      .add[Option[NodeId]]
      .add[None.type]
      .addCase(classOf[Leaf])
      .addCase(classOf[Branch])
      .build[Task]()
    import codecs._

    for {
      rocksDb ← rocksFactory(storeName, conf)
      // RootId=0L is always for root node, for other nodes id starts with 1
      isSeqProvider ← IdSeqProvider.longSeqProvider(rocksDb, RootId)
    } yield new BTreeBinaryStore[Task, NodeId, Node](rocksDb, isSeqProvider)
  }

  /**
   * Task for persisting. Contains updated node after inserting new value and rebalancing the tree.
   *
   * @param nodesToSave   Pool of changed nodes that should be persisted to tree store
   * @param increaseDepth If root node was splitted than tree depth should be increased.
   *                      If true - tree depth will be increased in physical state, if false - depth won't changed.
   *                      Note that each put operation might increase root depth only by one.
   * @param wasSplitting Indicator of the fact that during putting there was a rebalancing
   */
  case class PutTask(
    nodesToSave: Seq[NodeWithId[NodeId, Node]],
    increaseDepth: Boolean = false,
    wasSplitting: Boolean = false
  )

}
