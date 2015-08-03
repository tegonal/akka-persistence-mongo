package akka.contrib.persistence.mongodb

import akka.persistence._
import com.mongodb.DBObject
import com.mongodb.casbah.Imports._

import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class CasbahPersistenceJournaller(driver: CasbahMongoDriver) extends MongoPersistenceJournallingApi {

  import CasbahSerializers._

  implicit val system = driver.actorSystem

  private[this] implicit val serialization = driver.serialization
  private[this] lazy val writeConcern = driver.journalWriteConcern

  private[this] def journalRangeQuery(pid: String, from: Long, to: Long): DBObject =
    (PROCESSOR_ID $eq pid) ++ (FROM $gte from) ++ (FROM $lte to)

  private[this] def journal(implicit ec: ExecutionContext) = driver.journal

  private[mongodb] def journalRange(pid: String, from: Long, to: Long)(implicit ec: ExecutionContext): Iterator[Event] =
    journal.find(journalRangeQuery(pid, from, to))
           .flatMap(_.getAs[MongoDBList](EVENTS))
           .flatMap(lst => lst.collect { case x:DBObject => x })
           .filter(dbo => dbo.getAs[Long](SEQUENCE_NUMBER).exists(sn => sn >= from && sn <= to))
           .map(driver.deserializeJournal)

  private[mongodb] override def atomicAppend(write: AtomicWrite)(implicit ec: ExecutionContext):Future[Try[Unit]] = Future {
    Try(driver.serializeJournal(Atom[DBObject](write)))
      .map(serialized => journal.insert(serialized)(identity, writeConcern))
      .map(_ => ())
  }

  private[mongodb] override def deleteFrom(persistenceId: String, toSequenceNr: Long)(implicit ec: ExecutionContext): Future[Unit] = Future {
    val query = journalRangeQuery(persistenceId, 0L, toSequenceNr)
    val pull = MongoDBObject(
      "$pull" -> MongoDBObject(
        EVENTS -> MongoDBObject(
          PROCESSOR_ID -> persistenceId,
          SEQUENCE_NUMBER -> MongoDBObject("$lte" -> toSequenceNr)
        )),
      "$set" -> MongoDBObject(FROM -> (toSequenceNr+1))
    )
    journal.update(query, pull, upsert = false, multi = true, writeConcern)
    journal.remove($and(query, EVENTS $size 0), writeConcern)
    ()
  }

  private[mongodb] def maxSequenceNr(pid: String, from: Long)(implicit ec: ExecutionContext): Future[Long] = Future {
    val query = PROCESSOR_ID $eq pid
    val projection = MongoDBObject(TO -> 1)
    val sort = MongoDBObject(TO -> -1)
    val max = journal.find(query, projection).sort(sort).limit(1).one()
    max.getAs[Long](TO).getOrElse(0L)
  }

  private[mongodb] override def replayJournal(pid: String, from: Long, to: Long, max: Long)(replayCallback: PersistentRepr ⇒ Unit)(implicit ec: ExecutionContext) = Future {
    @tailrec
    def replayLimit(cursor: Iterator[Event], remaining: Long): Unit = if (remaining > 0 && cursor.hasNext) {
      replayCallback(cursor.next().toRepr)
      replayLimit(cursor, remaining - 1)
    }

    if (to >= from) {
      replayLimit(journalRange(pid, from, to), max)
    }
  }

}