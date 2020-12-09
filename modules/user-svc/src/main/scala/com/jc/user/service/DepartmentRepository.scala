package com.jc.user.service

import akka.actor.typed.ActorSystem
import com.jc.user.domain.DepartmentEntity
import com.sksamuel.elastic4s.ElasticClient

import scala.concurrent.{ ExecutionContext, Future }

trait DepartmentRepository[F[_]] extends Repository[F, DepartmentEntity.DepartmentId, DepartmentRepository.Department]

object DepartmentRepository {

  final case class Department(
      id: DepartmentEntity.DepartmentId,
      name: String,
      description: String
  ) extends Repository.Entity[DepartmentEntity.DepartmentId]

  object Department {
    import shapeless._

    val nameLens: Lens[Department, String]        = lens[Department].name
    val descriptionLens: Lens[Department, String] = lens[Department].description

    val nameDescriptionLens: ProductLensBuilder[Department, (String, String)] = nameLens ~ descriptionLens

    import io.circe._, io.circe.generic.semiauto._
    implicit val departmentDecoder: Decoder[Department] = deriveDecoder[Department]
    implicit val departmentEncoder: Encoder[Department] = deriveEncoder[Department]
  }
}

object DepartmentESRepository {

  def apply(indexName: String, elasticClient: ElasticClient)(implicit ec: ExecutionContext): DepartmentRepository[Future] =
    new ESRepository[DepartmentEntity.DepartmentId, DepartmentRepository.Department](indexName, elasticClient)
      with DepartmentRepository[Future]
}

object DepartmentESRepositoryInitializer {
  import com.sksamuel.elastic4s.ElasticDsl._

  val fields = Seq(
    textField("id").fielddata(true),
    textField("name").fielddata(true),
    textField("description").fielddata(true)
  )

  def apply(
      indexName: String,
      elasticClient: ElasticClient
  )(implicit ec: ExecutionContext, system: ActorSystem[_]): RepositoryInitializer[Future] =
    new ESRepositoryInitializer(indexName, fields, elasticClient)

}
