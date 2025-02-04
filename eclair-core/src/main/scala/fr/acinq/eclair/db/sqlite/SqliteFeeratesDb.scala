/*
 * Copyright 2020 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.db.sqlite

import fr.acinq.bitcoin.Satoshi
import fr.acinq.eclair.TimestampMilli
import fr.acinq.eclair.blockchain.fee.{FeeratePerKB, FeeratesPerKB}
import fr.acinq.eclair.db.FeeratesDb
import grizzled.slf4j.Logging

import java.sql.{Connection, Statement}

object SqliteFeeratesDb {
  val DB_NAME = "feerates"
  val CURRENT_VERSION = 2
}

class SqliteFeeratesDb(sqlite: Connection) extends FeeratesDb with Logging {

  import SqliteFeeratesDb._
  import SqliteUtils.ExtendedResultSet._
  import SqliteUtils._

  using(sqlite.createStatement(), inTransaction = true) { statement =>

    def migration12(statement: Statement): Unit = {
      statement.executeUpdate("ALTER TABLE feerates_per_kb RENAME TO _feerates_per_kb_old")
      statement.executeUpdate(
        """
          |CREATE TABLE feerates_per_kb (
          |rate_block_1 INTEGER NOT NULL, rate_blocks_2 INTEGER NOT NULL, rate_blocks_6 INTEGER NOT NULL, rate_blocks_12 INTEGER NOT NULL, rate_blocks_36 INTEGER NOT NULL, rate_blocks_72 INTEGER NOT NULL, rate_blocks_144 INTEGER NOT NULL, rate_blocks_1008 INTEGER NOT NULL,
          |timestamp INTEGER NOT NULL)""".stripMargin)
      statement.executeUpdate("INSERT INTO feerates_per_kb (rate_block_1, rate_blocks_2, rate_blocks_6, rate_blocks_12, rate_blocks_36, rate_blocks_72, rate_blocks_144, rate_blocks_1008, timestamp) SELECT rate_block_1, rate_blocks_2, rate_blocks_6, rate_blocks_12, rate_blocks_36, rate_blocks_72, rate_blocks_144, rate_blocks_144, timestamp FROM _feerates_per_kb_old")
      statement.executeUpdate("DROP table _feerates_per_kb_old")
    }

    getVersion(statement, DB_NAME) match {
      case None =>
        // Create feerates table. Rates are in kb.
        statement.executeUpdate(
          """
            |CREATE TABLE feerates_per_kb (
            |rate_block_1 INTEGER NOT NULL, rate_blocks_2 INTEGER NOT NULL, rate_blocks_6 INTEGER NOT NULL, rate_blocks_12 INTEGER NOT NULL, rate_blocks_36 INTEGER NOT NULL, rate_blocks_72 INTEGER NOT NULL, rate_blocks_144 INTEGER NOT NULL, rate_blocks_1008 INTEGER NOT NULL,
            |timestamp INTEGER NOT NULL)""".stripMargin)
      case Some(v@1) =>
        logger.warn(s"migrating db $DB_NAME, found version=$v current=$CURRENT_VERSION")
        migration12(statement)
      case Some(CURRENT_VERSION) => () // table is up-to-date, nothing to do
      case Some(unknownVersion) => throw new RuntimeException(s"Unknown version of DB $DB_NAME found, version=$unknownVersion")
    }
    setVersion(statement, DB_NAME, CURRENT_VERSION)
  }

  override def addOrUpdateFeerates(feeratesPerKB: FeeratesPerKB): Unit = {
    using(sqlite.prepareStatement("UPDATE feerates_per_kb SET rate_block_1=?, rate_blocks_2=?, rate_blocks_6=?, rate_blocks_12=?, rate_blocks_36=?, rate_blocks_72=?, rate_blocks_144=?, rate_blocks_1008=?, timestamp=?")) { update =>
      update.setLong(1, feeratesPerKB.block_1.toLong)
      update.setLong(2, feeratesPerKB.blocks_2.toLong)
      update.setLong(3, feeratesPerKB.blocks_6.toLong)
      update.setLong(4, feeratesPerKB.blocks_12.toLong)
      update.setLong(5, feeratesPerKB.blocks_36.toLong)
      update.setLong(6, feeratesPerKB.blocks_72.toLong)
      update.setLong(7, feeratesPerKB.blocks_144.toLong)
      update.setLong(8, feeratesPerKB.blocks_1008.toLong)
      update.setLong(9, TimestampMilli.now().toLong)
      if (update.executeUpdate() == 0) {
        using(sqlite.prepareStatement("INSERT INTO feerates_per_kb VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) { insert =>
          insert.setLong(1, feeratesPerKB.block_1.toLong)
          insert.setLong(2, feeratesPerKB.blocks_2.toLong)
          insert.setLong(3, feeratesPerKB.blocks_6.toLong)
          insert.setLong(4, feeratesPerKB.blocks_12.toLong)
          insert.setLong(5, feeratesPerKB.blocks_36.toLong)
          insert.setLong(6, feeratesPerKB.blocks_72.toLong)
          insert.setLong(7, feeratesPerKB.blocks_144.toLong)
          insert.setLong(8, feeratesPerKB.blocks_1008.toLong)
          insert.setLong(9, TimestampMilli.now().toLong)
          insert.executeUpdate()
        }
      }
    }
  }

  override def getFeerates(): Option[FeeratesPerKB] = {
    using(sqlite.prepareStatement("SELECT rate_block_1, rate_blocks_2, rate_blocks_6, rate_blocks_12, rate_blocks_36, rate_blocks_72, rate_blocks_144, rate_blocks_1008 FROM feerates_per_kb")) { statement =>
      statement.executeQuery()
        .map { rs =>
          FeeratesPerKB(
            // NB: we don't bother storing this value in the DB, because it's unused on mobile.
            mempoolMinFee = FeeratePerKB(Satoshi(rs.getLong("rate_blocks_1008"))),
            block_1 = FeeratePerKB(Satoshi(rs.getLong("rate_block_1"))),
            blocks_2 = FeeratePerKB(Satoshi(rs.getLong("rate_blocks_2"))),
            blocks_6 = FeeratePerKB(Satoshi(rs.getLong("rate_blocks_6"))),
            blocks_12 = FeeratePerKB(Satoshi(rs.getLong("rate_blocks_12"))),
            blocks_36 = FeeratePerKB(Satoshi(rs.getLong("rate_blocks_36"))),
            blocks_72 = FeeratePerKB(Satoshi(rs.getLong("rate_blocks_72"))),
            blocks_144 = FeeratePerKB(Satoshi(rs.getLong("rate_blocks_144"))),
            blocks_1008 = FeeratePerKB(Satoshi(rs.getLong("rate_blocks_1008"))))
        }
        .headOption
    }
  }

  // used by mobile apps
  override def close(): Unit = sqlite.close()
}
