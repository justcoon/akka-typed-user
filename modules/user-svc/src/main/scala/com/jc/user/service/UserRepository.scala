package com.jc.user.service

import akka.actor.typed.ActorSystem
import com.jc.user.domain.UserEntity.UserId
import com.jc.user.domain.{ DepartmentEntity, UserEntity }
import com.sksamuel.elastic4s.ElasticClient

import scala.concurrent.{ ExecutionContext, Future }

trait UserRepository[F[_]] extends Repository[F, UserEntity.UserId, UserRepository.User] with SearchRepository[Future, UserRepository.User]

object UserRepository {

  final case class Department(
      id: DepartmentEntity.DepartmentId
  ) extends Repository.Entity[DepartmentEntity.DepartmentId]

  object Department {
    import io.circe._
    import io.circe.generic.semiauto._
    implicit val departmentDecoder: Decoder[Department] = deriveDecoder[Department]
    implicit val departmentEncoder: Encoder[Department] = deriveEncoder[Department]
  }

  final case class Address(
      street: String,
      number: String,
      zip: String,
      city: String,
      state: String,
      country: String
  )

  object Address {
    import io.circe._
    import io.circe.generic.semiauto._

    implicit val addressDecoder: Decoder[Address] = deriveDecoder[Address]

    implicit val addressEncoder: Encoder[Address] = deriveEncoder[Address]
  }

  final case class User(
      id: UserEntity.UserId,
      username: String,
      email: String,
      pass: String,
      address: Option[Address] = None,
      department: Option[Department] = None
  ) extends Repository.Entity[UserEntity.UserId]

  object User {
    import shapeless._

    val usernameLens: Lens[User, String]               = lens[User].username
    val emailLens: Lens[User, String]                  = lens[User].email
    val passLens: Lens[User, String]                   = lens[User].pass
    val addressLens: Lens[User, Option[Address]]       = lens[User].address
    val departmentLens: Lens[User, Option[Department]] = lens[User].department

    val usernameEmailPassAddressDepartmentLens: ProductLensBuilder[User, (String, String, String, Option[Address], Option[Department])] =
      usernameLens ~ emailLens ~ passLens ~ addressLens ~ departmentLens

    import io.circe._
    import io.circe.generic.semiauto._

    implicit val userDecoder: Decoder[User] = deriveDecoder[User]

    implicit val userEncoder: Encoder[User] = new Encoder[User] {

      val derived: Encoder[User] = deriveEncoder[User]

      override def apply(a: User): Json =
        derived(a).mapObject { jo =>
          jo.add(ElasticUtils.getSuggestPropertyName("username"), Json.fromString(a.username))
            .add(ElasticUtils.getSuggestPropertyName("email"), Json.fromString(a.email))
        }
    }
  }
}

object UserESRepository {

  def apply(indexName: String, elasticClient: ElasticClient)(implicit ec: ExecutionContext): UserRepository[Future] = {
    val repo       = new ESRepository[UserEntity.UserId, UserRepository.User](indexName, elasticClient)
    val searchRepo = new ESSearchRepository[UserRepository.User](indexName, UserESRepositoryInitializer.suggestProperties, elasticClient)

    new UserRepository[Future] {
      override def insert(value: UserRepository.User): Future[Boolean] = repo.insert(value)

      override def update(value: UserRepository.User): Future[Boolean] = repo.update(value)

      override def delete(id: UserId): Future[Boolean] = repo.delete(id)

      override def find(id: UserId): Future[Option[UserRepository.User]] = repo.find(id)

      override def findAll(): Future[Seq[UserRepository.User]] = repo.findAll()

      override def search(
          query: Option[String],
          page: Int,
          pageSize: Int,
          sorts: Iterable[SearchRepository.FieldSort]
      ): Future[Either[SearchRepository.SearchError, SearchRepository.PaginatedSequence[UserRepository.User]]] =
        searchRepo.search(query, page, pageSize, sorts)

      override def suggest(query: String): Future[Either[SearchRepository.SuggestError, SearchRepository.SuggestResponse]] =
        searchRepo.suggest(query)
    }
  }
}

object UserESRepositoryInitializer {
  import com.sksamuel.elastic4s.ElasticDsl._

  val suggestProperties = Seq("username", "email")

  val fields = Seq(
    textField("id").fielddata(true),
    textField("username").fielddata(true),
    textField("email").fielddata(true),
    textField("address.street").fielddata(true),
    textField("address.number").fielddata(true),
    textField("address.city").fielddata(true),
    textField("address.state").fielddata(true),
    textField("address.zip").fielddata(true),
    textField("address.country").fielddata(true),
    textField("department.id").fielddata(true)
  ) ++ suggestProperties.map(prop => completionField(ElasticUtils.getSuggestPropertyName(prop)))

  def apply(
      indexName: String,
      elasticClient: ElasticClient
  )(implicit ec: ExecutionContext, system: ActorSystem[_]): RepositoryInitializer[Future] =
    new ESRepositoryInitializer(indexName, fields, elasticClient)

}
