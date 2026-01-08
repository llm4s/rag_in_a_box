package org.llm4s.ragbox.auth

import cats.effect.IO
import cats.effect.Ref
import cats.syntax.all.*

/** Registry for OAuth authorization state (PKCE) and sessions */
trait SessionRegistry[F[_]]:
  /** Store PKCE authorization state during login flow */
  def storeAuthState(state: AuthorizationState): F[Unit]

  /** Retrieve and remove auth state (one-time use) */
  def getAndRemoveAuthState(state: String): F[Option[AuthorizationState]]

  /** Create a new session after successful authentication */
  def createSession(session: OAuthSessionData): F[Unit]

  /** Get session by ID */
  def getSession(sessionId: String): F[Option[OAuthSessionData]]

  /** Delete a session (logout) */
  def deleteSession(sessionId: String): F[Unit]

  /** Clean up expired state and sessions, returns count removed */
  def cleanupExpired(): F[Int]

/** In-memory implementation for development and testing */
class InMemorySessionRegistry private (
    authStates: Ref[IO, Map[String, AuthorizationState]],
    sessions: Ref[IO, Map[String, OAuthSessionData]]
) extends SessionRegistry[IO]:

  override def storeAuthState(state: AuthorizationState): IO[Unit] =
    authStates.update(_ + (state.state -> state))

  override def getAndRemoveAuthState(
      state: String
  ): IO[Option[AuthorizationState]] =
    authStates.modify { states =>
      states.get(state) match
        case Some(authState) => (states - state, Some(authState))
        case None            => (states, None)
    }

  override def createSession(session: OAuthSessionData): IO[Unit] =
    sessions.update(_ + (session.sessionId -> session))

  override def getSession(sessionId: String): IO[Option[OAuthSessionData]] =
    sessions.get.map(_.get(sessionId))

  override def deleteSession(sessionId: String): IO[Unit] =
    sessions.update(_ - sessionId)

  override def cleanupExpired(): IO[Int] =
    val now = System.currentTimeMillis()
    for
      statesRemoved <- authStates.modify { states =>
        val (expired, valid) = states.partition { case (_, s) =>
          // Auth states expire based on stateTtl (default 5 min)
          (now - s.createdAt) > 300000
        }
        (valid, expired.size)
      }
      sessionsRemoved <- sessions.modify { sess =>
        val (expired, valid) = sess.partition { case (_, s) =>
          s.expiresAt < now
        }
        (valid, expired.size)
      }
    yield statesRemoved + sessionsRemoved

  // Test helpers for inspection
  def getStoredStates: IO[Map[String, AuthorizationState]] = authStates.get
  def getStoredSessions: IO[Map[String, OAuthSessionData]] = sessions.get

object InMemorySessionRegistry:
  def create: IO[InMemorySessionRegistry] =
    for
      authStates <- Ref.of[IO, Map[String, AuthorizationState]](Map.empty)
      sessions <- Ref.of[IO, Map[String, OAuthSessionData]](Map.empty)
    yield new InMemorySessionRegistry(authStates, sessions)
