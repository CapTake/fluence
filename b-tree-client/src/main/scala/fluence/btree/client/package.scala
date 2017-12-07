package fluence.btree

import cats.Show

package object client {

  type Key = Array[Byte]
  type Value = Array[Byte]
  type Bytes = Array[Byte]

  implicit private[client] val showBytes: Show[Key] =
    (b: Key) ⇒ new String(b)

}
