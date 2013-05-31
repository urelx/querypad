package models

import anorm._
import play.api.Logger
import play.api.db._
import play.api.Play.current
import org.joda.time.DateTime
import java.util.Date
import java.sql.Connection
import java.io.File

import play.api._
import play.api.mvc._
import play.api.mvc.Results._

case class DBQuery(sql: String, name: String = "", save: Boolean = false)
case class DBQueryResult(columns: List[String], rows: List[List[Any]])
case class DBQueryHistory(id: Pk[Int], name: String, query: DBQuery, updatedAt: DateTime)

object DBQuery {
  val QueryHistoryTable = "query_history"

  private def execute(query: DBQuery)(implicit conn: Connection):
      (List[String], Stream[List[Any]]) = {
    val rs = SQL(query.sql).resultSet()
    val columns = Sql.metaData(rs).ms map { mdItem =>
      mdItem.column.alias getOrElse mdItem.column.qualified
    }

    (columns, Sql.resultSetToStream(rs).map(_.data))
  }

  def execute(query: DBQuery, maxRows: Int = 1000): DBQueryResult = {
    DB.withConnection("ext") { implicit conn =>
      execute(query) match {
        case (columns, rows) => DBQueryResult(columns, rows.take(maxRows).toList)
      }
    }
  }

  def executeWithHandler[T](query: DBQuery)
      (handler: (List[String], Stream[List[Any]]) => T): T = {
    DB.withConnection("ext") { implicit conn =>
      execute(query) match {
        case (columns, rows) => handler(columns, rows)
      }
    }
  }

  def addHistory(query: DBQuery) =
    DB.withConnection { implicit conn =>
      SQL(s"""INSERT INTO $QueryHistoryTable VALUES(
        nextval('query_history_seq'), {name}, {sql}, current_timestamp)""")
        .on("name" -> query.name, "sql" -> query.sql).executeInsert()
    }

  def listHistory(limit: Int): List[DBQueryHistory] = {
    DB.withConnection { implicit conn =>
      SQL(s"""SELECT * FROM $QueryHistoryTable
        ORDER BY updated_at DESC LIMIT {limit}""")
          .on("limit" -> limit)() map { row =>
            DBQueryHistory(row[Pk[Int]]("id"), row[String]("name"),
              DBQuery(row[String]("sql")), new DateTime(row[Date]("updated_at")))
          } toList
    }
  }

  def removeHistory(id: Int) {
    DB.withConnection { implicit conn =>
      SQL(s"""DELETE FROM $QueryHistoryTable WHERE id = {id}""")
        .on("id" -> id).execute()
    }
  }
}
