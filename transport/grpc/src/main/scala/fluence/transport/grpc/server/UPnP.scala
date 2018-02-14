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

package fluence.transport.grpc.server

import java.net.InetAddress

import fluence.kad.protocol.Contact
import monix.eval.Task
import org.bitlet.weupnp.{ GatewayDevice, GatewayDiscover }

import scala.collection.JavaConverters._
import scala.concurrent.duration.Duration

// TODO: find a way to cover with tests: mock external upnp, check our logic
/**
 * UPnP port forwarding utility
 * @param clientName Client name to register on gateway
 * @param protocol Protocol name to register on gateway
 * @param httpReadTimeout Http read timeout
 * @param discoverTimeout Devices discovery timeout
 */
class UPnP(
    clientName: String = "Fluence",
    protocol: String = "TCP",
    httpReadTimeout: Duration = Duration.Undefined,
    discoverTimeout: Duration = Duration.Undefined
) extends slogging.LazyLogging {

  /**
   * Memoized gateway task
   */
  val gateway: Task[GatewayDevice] = Task {
    logger.info("Going to discover GatewayDevice...")
    GatewayDevice.setHttpReadTimeout(
      Option(httpReadTimeout).filter(_.isFinite()).map(_.toMillis.toInt).getOrElse(GatewayDevice.getHttpReadTimeout)
    )

    val discover = new GatewayDiscover()
    discover.setTimeout(
      Option(discoverTimeout).filter(_.isFinite()).map(_.toMillis.toInt).getOrElse(GatewayDevice.getHttpReadTimeout)
    )

    discover
  }.flatMap {
    discover ⇒
      Task(
        Option(discover.discover).map(_.asScala).map(_.toMap).getOrElse(Map())
      ).flatMap[GatewayDiscover] { gatewayMap ⇒
          if (gatewayMap.isEmpty) {
            logger.warn("Gateway map is empty")
            Task.raiseError[GatewayDiscover](new NoSuchElementException("Gateway map is empty"))
          } else Task.now(discover)
        }
  }.flatMap { discover ⇒
    Option(discover.getValidGateway) match {
      case None ⇒
        logger.warn("There is no connected UPnP gateway device")
        Task.raiseError[GatewayDevice](new NoSuchElementException("There is no connected UPnP gateway device"))

      case Some(device) ⇒
        logger.info("Found device: " + device)
        Task.now(device)
    }
  }.memoizeOnSuccess

  /**
   * Memoized external address
   */
  val externalAddress: Task[InetAddress] =
    gateway
      .flatMap(gw ⇒ Task(gw.getExternalIPAddress))
      .map(InetAddress.getByName)
      .map{ addr ⇒
        logger.info("External IP address: {}", addr.getHostAddress)
        addr
      }
      .memoizeOnSuccess

  /**
   * Add external port on gateway or fail with exception
   * @param externalPort External port to forward to local port
   * @param contact To get localPort from, and set addr and external port to TODO it should not be there?
   * @return Contact with external address
   */
  def addPort(externalPort: Int, contact: Contact): Task[Contact] =
    gateway.flatMap { gw ⇒
      logger.info("Going to add port mapping: {} => {}", externalPort, contact.port)
      Task(
        gw.addPortMapping(externalPort, contact.port, gw.getLocalAddress.getHostAddress, protocol, clientName)
      ).flatMap {
          case true ⇒
            logger.info("External port successfully mapped")
            externalAddress.map(addr ⇒ contact.copy(ip = addr, port = externalPort))

          case false ⇒
            logger.warn("Can't add port mapping")
            Task.raiseError(new RuntimeException("Can't add port mapping"))
        }
    }

  /**
   * Remove external port mapping on gateway
   * @param externalPort External port with registered mapping
   * @return
   */
  def deletePort(externalPort: Int): Task[Unit] =
    gateway.flatMap(gw ⇒ Task(gw.deletePortMapping(externalPort, protocol)))

}
