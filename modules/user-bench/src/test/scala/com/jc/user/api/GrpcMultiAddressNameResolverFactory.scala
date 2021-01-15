package com.jc.user.api

import io.grpc.Attributes
import io.grpc.EquivalentAddressGroup
import io.grpc.NameResolver
import io.grpc.NameResolver.ResolutionResult

import java.net.{ SocketAddress, URI }

class GrpcMultiAddressNameResolverFactory(addresses: List[SocketAddress]) extends NameResolver.Factory {
  import scala.jdk.CollectionConverters._
  private val resolutionAddresses = addresses.map(a => new EquivalentAddressGroup(a)).asJava

  override def newNameResolver(targetUri: URI, args: NameResolver.Args): NameResolver = new NameResolver {
    override def getServiceAuthority: String = "fakeAuthority"

    override def start(listener: NameResolver.Listener2): Unit =
      listener.onResult(ResolutionResult.newBuilder().setAddresses(resolutionAddresses).setAttributes(Attributes.EMPTY).build())

    override def shutdown(): Unit =
      ()
  }
  override def getDefaultScheme: String = "multiaddress"
}
