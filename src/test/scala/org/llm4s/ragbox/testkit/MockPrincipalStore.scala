package org.llm4s.ragbox.testkit

import org.llm4s.rag.permissions._
import org.llm4s.types.Result

import scala.collection.mutable

/**
 * Mock PrincipalStore for testing OAuth claim mapping.
 *
 * Provides an in-memory implementation that tracks all created principals
 * and allows test assertions.
 */
class MockPrincipalStore extends PrincipalStore:

  private val principals = mutable.Map.empty[ExternalPrincipal, PrincipalId]
  private val idToExternal = mutable.Map.empty[PrincipalId, ExternalPrincipal]
  private var nextUserId = 1      // Users get positive IDs
  private var nextGroupId = -1    // Groups get negative IDs (library requirement)
  private val createdUsers = mutable.ListBuffer.empty[String]
  private val createdGroups = mutable.ListBuffer.empty[String]

  override def getOrCreate(external: ExternalPrincipal): Result[PrincipalId] =
    principals.get(external) match
      case Some(id) => Right(id)
      case None =>
        val id = external match
          case ExternalPrincipal.User(_) =>
            val id = PrincipalId(nextUserId)
            nextUserId += 1
            id
          case ExternalPrincipal.Group(_) =>
            val id = PrincipalId(nextGroupId)
            nextGroupId -= 1
            id
        principals.put(external, id)
        idToExternal.put(id, external)
        external match
          case ExternalPrincipal.User(userId) => createdUsers += userId
          case ExternalPrincipal.Group(groupId) => createdGroups += groupId
        Right(id)

  override def getOrCreateBatch(externals: Seq[ExternalPrincipal]): Result[Map[ExternalPrincipal, PrincipalId]] =
    Right(externals.map(e => e -> getOrCreate(e).toOption.get).toMap)

  override def lookup(external: ExternalPrincipal): Result[Option[PrincipalId]] =
    Right(principals.get(external))

  override def lookupBatch(externals: Seq[ExternalPrincipal]): Result[Map[ExternalPrincipal, PrincipalId]] =
    Right(externals.flatMap(e => principals.get(e).map(id => e -> id)).toMap)

  override def getExternalId(id: PrincipalId): Result[Option[ExternalPrincipal]] =
    Right(idToExternal.get(id))

  override def list(principalType: String, limit: Int, offset: Int): Result[Seq[ExternalPrincipal]] =
    val filtered = principalType match
      case "user" => principals.keys.collect { case u: ExternalPrincipal.User => u: ExternalPrincipal }.toSeq
      case "group" => principals.keys.collect { case g: ExternalPrincipal.Group => g: ExternalPrincipal }.toSeq
      case _ => Seq.empty
    Right(filtered.drop(offset).take(limit))

  override def count(principalType: String): Result[Long] =
    val filtered = principalType match
      case "user" => principals.keys.count(_.isInstanceOf[ExternalPrincipal.User])
      case "group" => principals.keys.count(_.isInstanceOf[ExternalPrincipal.Group])
      case _ => 0
    Right(filtered.toLong)

  override def delete(external: ExternalPrincipal): Result[Unit] =
    principals.remove(external).foreach { id =>
      idToExternal.remove(id)
    }
    Right(())

  // Test helper methods

  def getCreatedUsers: Seq[String] = createdUsers.toSeq

  def getCreatedGroups: Seq[String] = createdGroups.toSeq

  def reset(): Unit =
    principals.clear()
    idToExternal.clear()
    createdUsers.clear()
    createdGroups.clear()
    nextUserId = 1
    nextGroupId = -1

object MockPrincipalStore:
  def apply(): MockPrincipalStore = new MockPrincipalStore()
