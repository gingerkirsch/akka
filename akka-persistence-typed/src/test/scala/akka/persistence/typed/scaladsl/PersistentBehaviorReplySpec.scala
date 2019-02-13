/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.scaladsl

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.duration._
import scala.util.Success
import scala.util.Try

import akka.Done
import akka.actor.testkit.typed.TestKitSettings
import akka.actor.testkit.typed.scaladsl._
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.SupervisorStrategy
import akka.actor.typed.Terminated
import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.journal.inmem.InmemJournal
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import akka.persistence.query.EventEnvelope
import akka.persistence.query.PersistenceQuery
import akka.persistence.query.Sequence
import akka.persistence.typed.ExpectingReply
import akka.persistence.typed.PersistenceId
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.scalatest.WordSpecLike

object PersistentBehaviorReplySpec {
  def conf: Config = ConfigFactory.parseString(
    s"""
    akka.loglevel = INFO
    # akka.persistence.typed.log-stashing = INFO
    akka.persistence.journal.leveldb.dir = "target/typed-persistence-${UUID.randomUUID().toString}"
    akka.persistence.journal.plugin = "akka.persistence.journal.leveldb"
    akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"
    akka.persistence.snapshot-store.local.dir = "target/typed-persistence-${UUID.randomUUID().toString}"
    """)

  sealed trait Command[ReplyMessage] extends ExpectingReply[ReplyMessage]
  final case class IncrementWithConfirmation(override val replyTo: ActorRef[Done]) extends Command[Done]
  final case class IncrementReplyLater(override val replyTo: ActorRef[Done]) extends Command[Done]
  final case class ReplyNow(override val replyTo: ActorRef[Done]) extends Command[Done]
  final case class GetValue(replyTo: ActorRef[State]) extends Command[State]

  sealed trait Event
  final case class Incremented(delta: Int) extends Event

  final case class State(value: Int, history: Vector[Int])

  def counter(persistenceId: PersistenceId)(implicit system: ActorSystem[_]): Behavior[Command[_]] =
    Behaviors.setup(ctx ⇒ counter(ctx, persistenceId))

  def counter(
    ctx:           ActorContext[Command[_]],
    persistenceId: PersistenceId): PersistentBehavior[Command[_], Event, State] = {
    PersistentBehavior.withEnforcedReplies[Command[_], Event, State](
      persistenceId,
      emptyState = State(0, Vector.empty),
      commandHandler = (state, cmd) ⇒ cmd match {

        case cmd: IncrementWithConfirmation ⇒
          Effect.persist(Incremented(1))
            .thenReply(cmd)(_ ⇒ Done)

        case cmd: IncrementReplyLater ⇒
          Effect.persist(Incremented(1))
            .thenRun((_: State) ⇒ ctx.self ! ReplyNow(cmd.replyTo))
            .thenNoReply()

        case cmd: ReplyNow ⇒
          Effect.reply(cmd)(Done)

        case query: GetValue ⇒
          Effect.reply(query)(state)

      },
      eventHandler = (state, evt) ⇒ evt match {
        case Incremented(delta) ⇒
          State(state.value + delta, state.history :+ state.value)
      })
  }
}

class PersistentBehaviorReplySpec extends ScalaTestWithActorTestKit(PersistentBehaviorSpec.conf) with WordSpecLike {

  import PersistentBehaviorReplySpec._

  val pidCounter = new AtomicInteger(0)
  private def nextPid(): PersistenceId = PersistenceId(s"c${pidCounter.incrementAndGet()})")

  "A typed persistent actor with commands that are expecting replies" must {

    "persist an event thenReply" in {
      val c = spawn(counter(nextPid()))
      val probe = TestProbe[Done]
      c ! IncrementWithConfirmation(probe.ref)
      probe.expectMessage(Done)

      c ! IncrementWithConfirmation(probe.ref)
      c ! IncrementWithConfirmation(probe.ref)
      probe.expectMessage(Done)
      probe.expectMessage(Done)
    }

    "persist an event thenReply later" in {
      val c = spawn(counter(nextPid()))
      val probe = TestProbe[Done]
      c ! IncrementReplyLater(probe.ref)
      probe.expectMessage(Done)
    }

    "reply to query command" in {
      val c = spawn(counter(nextPid()))
      val updateProbe = TestProbe[Done]
      c ! IncrementWithConfirmation(updateProbe.ref)

      val queryProbe = TestProbe[State]
      c ! GetValue(queryProbe.ref)
      queryProbe.expectMessage(State(1, Vector(0)))
    }
  }
}