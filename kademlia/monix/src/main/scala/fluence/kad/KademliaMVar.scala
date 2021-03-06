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

package fluence.kad

import java.time.Instant

import cats.data.StateT
import cats.effect.IO
import cats.kernel.Monoid
import fluence.kad.protocol.{KademliaRpc, Key, Node}
import monix.eval.{MVar, Task}
import monix.execution.Scheduler
import monix.execution.atomic.AtomicAny

import scala.language.implicitConversions

// TODO: write unit tests
object KademliaMVar {

  /**
   * Kademlia service to be launched as a singleton on local node.
   *
   * @param nodeId        Current node ID
   * @param contact       Node's contact to advertise
   * @param rpcForContact Getter for RPC calling of another nodes
   * @param conf          Kademlia conf
   * @param checkNode     Node could be saved to RoutingTable only if checker returns F[ true ]
   * @tparam C Contact info
   */
  def apply[C](
    nodeId: Key,
    contact: IO[C],
    rpcForContact: C ⇒ KademliaRpc[C],
    conf: KademliaConf,
    checkNode: Node[C] ⇒ IO[Boolean]
  ): Kademlia[Task, C] = {
    implicit val bucketOps: Bucket.WriteOps[Task, C] = MVarBucketOps.task[C](conf.maxBucketSize)
    implicit val siblingOps: Siblings.WriteOps[Task, C] = KademliaMVar.siblingsOps(nodeId, conf.maxSiblingsSize)

    Kademlia[Task, Task.Par, C](
      nodeId,
      conf.parallelism,
      conf.pingExpiresIn,
      checkNode,
      contact.map(c ⇒ Node(nodeId, Instant.now(), c)),
      rpcForContact
    )
  }

  /**
   * Builder for client-side implementation of KademliaMVar
   *
   * @param rpc       Getter for RPC calling of another nodes
   * @param conf      Kademlia conf
   * @param checkNode Node could be saved to RoutingTable only if checker returns F[ true ]
   * @tparam C Contact info
   */
  def client[C](
    rpc: C ⇒ KademliaRpc[C],
    conf: KademliaConf,
    checkNode: Node[C] ⇒ IO[Boolean]
  ): Kademlia[Task, C] =
    apply[C](
      Monoid.empty[Key],
      IO.raiseError(new IllegalStateException("Client may not have a Contact")),
      rpc,
      conf,
      checkNode
    )

  /**
   * Performs atomic update on a MVar, blocking asynchronously if another update is in progress.
   *
   * @param mvar       State variable
   * @param mod        Modifier
   * @param updateRead Callback to update read model
   * @tparam S State
   * @tparam T Return value
   */
  private def runOnMVar[S, T](mvar: MVar[S], mod: StateT[Task, S, T], updateRead: S ⇒ Unit): Task[T] =
    mvar.take.flatMap { init ⇒
      // Run modification
      mod.run(init).onErrorHandleWith { err ⇒
        // In case modification failed, write initial value back to MVar
        mvar.put(init).flatMap(_ ⇒ Task.raiseError(err))
      }
    }.flatMap {
      case (updated, value) ⇒
        // Update read and write states
        updateRead(updated)
        mvar.put(updated).map(_ ⇒ value)
    }

  /**
   * Builds asynchronous sibling ops with $maxSiblings nodes max.
   *
   * @param nodeId      Siblings are sorted by distance to this nodeId
   * @param maxSiblings Max number of closest siblings to store
   * @tparam C Node contacts type
   */
  private def siblingsOps[C](nodeId: Key, maxSiblings: Int): Siblings.WriteOps[Task, C] =
    new Siblings.WriteOps[Task, C] {
      private val readState = AtomicAny(Siblings[C](nodeId, maxSiblings))
      private val writeState = MVar(Siblings[C](nodeId, maxSiblings)).memoize

      override protected def run[T](mod: StateT[Task, Siblings[C], T]): Task[T] =
        for {
          ws ← writeState
          res ← runOnMVar(
            ws,
            mod,
            readState.set
          )
        } yield res

      override def read: Siblings[C] =
        readState.get
    }
}
