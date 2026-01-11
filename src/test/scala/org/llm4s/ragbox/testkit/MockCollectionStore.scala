package org.llm4s.ragbox.testkit

import org.llm4s.rag.permissions._
import org.llm4s.types.Result
import org.llm4s.error.LLMError

import scala.collection.mutable

/**
 * Mock CollectionStore for testing collection permission routes.
 *
 * Provides an in-memory implementation that tracks all created collections
 * and allows test assertions.
 */
class MockCollectionStore extends CollectionStore:

  private val collections = mutable.Map.empty[CollectionPath, Collection]
  private var nextId = 1

  // Simple error type that matches the LLMError interface
  private case class MockError(message: String) extends LLMError

  override def create(config: CollectionConfig): Result[Collection] =
    if collections.contains(config.path) then
      Left(MockError(s"Collection already exists: ${config.path.value}"))
    else
      val id = nextId
      nextId += 1
      val parentPath = config.path.parent
      val coll = Collection(
        id = id,
        path = config.path,
        parentPath = parentPath,
        queryableBy = config.queryableBy,
        isLeaf = config.isLeaf,
        metadata = config.metadata
      )
      collections.put(config.path, coll)
      Right(coll)

  override def ensureExists(config: CollectionConfig): Result[Collection] =
    collections.get(config.path) match
      case Some(coll) => Right(coll)
      case None => create(config)

  override def get(path: CollectionPath): Result[Option[Collection]] =
    Right(collections.get(path))

  override def getById(id: Int): Result[Option[Collection]] =
    Right(collections.values.find(_.id == id))

  override def list(pattern: CollectionPattern): Result[Seq[Collection]] =
    // Simplified implementation - return all collections for any pattern
    // In real impl, pattern matching would filter appropriately
    val result = pattern match
      case CollectionPattern.All =>
        collections.values.toSeq
      case CollectionPattern.Exact(path) =>
        collections.get(path).toSeq
      case _ =>
        // For wildcard patterns, return all collections
        // A proper implementation would filter based on the pattern
        collections.values.toSeq
    Right(result.sortBy(_.path.value))

  override def listChildren(parentPath: CollectionPath): Result[Seq[Collection]] =
    Right(collections.values.filter(_.parentPath.contains(parentPath)).toSeq)

  override def findAccessible(auth: UserAuthorization, pattern: CollectionPattern): Result[Seq[Collection]] =
    // Simplified: admins see all, others see public + collections they have access to
    val all = list(pattern).getOrElse(Seq.empty)
    if auth.isAdmin then
      Right(all)
    else
      val userPrincipals = auth.principalIds.toSet
      val accessible = all.filter { c =>
        c.isPublic || c.queryableBy.exists(userPrincipals.contains)
      }
      Right(accessible)

  override def canQuery(path: CollectionPath, auth: UserAuthorization): Result[Boolean] =
    collections.get(path) match
      case Some(coll) =>
        if auth.isAdmin then Right(true)
        else if coll.isPublic then Right(true)
        else Right(coll.queryableBy.exists(auth.principalIds.contains))
      case None => Right(false)

  override def updatePermissions(path: CollectionPath, queryableBy: Set[PrincipalId]): Result[Collection] =
    collections.get(path) match
      case Some(coll) =>
        val updated = coll.copy(queryableBy = queryableBy)
        collections.put(path, updated)
        Right(updated)
      case None =>
        Left(MockError(s"Collection not found: ${path.value}"))

  override def updateMetadata(path: CollectionPath, metadata: Map[String, String]): Result[Collection] =
    collections.get(path) match
      case Some(coll) =>
        val updated = coll.copy(metadata = metadata)
        collections.put(path, updated)
        Right(updated)
      case None =>
        Left(MockError(s"Collection not found: ${path.value}"))

  override def getEffectivePermissions(path: CollectionPath): Result[Set[PrincipalId]] =
    // Simplified: just return direct permissions
    collections.get(path) match
      case Some(coll) => Right(coll.queryableBy)
      case None => Right(Set.empty)

  override def delete(path: CollectionPath): Result[Unit] =
    collections.remove(path)
    Right(())

  override def stats(path: CollectionPath): Result[CollectionStats] =
    if collections.contains(path) then
      Right(CollectionStats(documentCount = 0, chunkCount = 0, subCollectionCount = 0))
    else
      Left(MockError(s"Collection not found: ${path.value}"))

  override def countDocuments(path: CollectionPath): Result[Long] =
    Right(0L)

  override def countChunks(path: CollectionPath): Result[Long] =
    Right(0L)

  // Test helper methods

  def getCollections: Seq[Collection] = collections.values.toSeq

  def reset(): Unit =
    collections.clear()
    nextId = 1

object MockCollectionStore:
  def apply(): MockCollectionStore = new MockCollectionStore()
