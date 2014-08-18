/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package play.api.db

import java.sql.{ Connection, Driver, DriverManager }
import javax.sql.DataSource

import scala.util.control.{ NonFatal, ControlThrowable }

import play.api.Configuration
import play.utils.{ ProxyDriver, Reflect }

/**
 * Database API.
 */
trait Database {

  /**
   * The configuration name for this database.
   */
  def name: String

  /**
   * The underlying JDBC data source for this database.
   */
  def dataSource: DataSource

  /**
   * The JDBC connection URL this database, i.e. `jdbc:...`
   * Normally retrieved via a connection.
   */
  def url: String

  /**
   * Get a JDBC connection from the underlying data source.
   * Autocommit is enabled by default.
   *
   * Don't forget to release the connection at some point by calling close().
   *
   * @param autocommit determines whether to autocommit the connection
   * @return a JDBC connection
   */
  def getConnection(): Connection

  /**
   * Get a JDBC connection from the underlying data source.
   *
   * Don't forget to release the connection at some point by calling close().
   *
   * @param autocommit determines whether to autocommit the connection
   * @return a JDBC connection
   */
  def getConnection(autocommit: Boolean): Connection

  /**
   * Execute a block of code, providing a JDBC connection.
   * The connection and all created statements are automatically released.
   *
   * @param block code to execute
   * @return the result of the code block
   */
  def withConnection[A](block: Connection => A): A

  /**
   * Execute a block of code in the scope of a JDBC transaction.
   * The connection and all created statements are automatically released.
   * The transaction is automatically committed, unless an exception occurs.
   *
   * @param block code to execute
   * @return the result of the code block
   */
  def withTransaction[A](block: Connection => A): A

  /**
   * Shutdown this database, closing the underlying data source.
   */
  def shutdown(): Unit

}

/**
 * Default implementation of the database API.
 * Provides driver registration and connection methods.
 */
abstract class DefaultDatabase(val name: String, configuration: Configuration, classLoader: ClassLoader) extends Database {

  // abstract methods to be implemented

  def createDataSource(): DataSource

  def closeDataSource(dataSource: DataSource): Unit

  // driver registration

  lazy val driver: Driver = {
    val driverClass = configuration.getString("driver").getOrElse {
      throw configuration.reportError(name, s"Missing configuration [db.$name.driver]")
    }
    try {
      val proxyDriver = new ProxyDriver(Reflect.createInstance[Driver](driverClass, classLoader))
      DriverManager.registerDriver(proxyDriver)
      proxyDriver
    } catch {
      case NonFatal(e) => throw configuration.reportError("driver", s"Driver not found: [$driverClass]", Some(e))
    }
  }

  // lazy data source creation

  lazy val dataSource: DataSource = {
    driver // trigger driver registration
    createDataSource
  }

  lazy val url: String = {
    val connection = dataSource.getConnection
    try {
      connection.getMetaData.getURL
    } finally {
      connection.close()
    }
  }

  // connection methods

  def getConnection(): Connection = {
    getConnection(autocommit = true)
  }

  def getConnection(autocommit: Boolean): Connection = {
    val connection = dataSource.getConnection
    connection.setAutoCommit(autocommit)
    connection
  }

  def withConnection[A](block: Connection => A): A = {
    val connection = new AutoCleanConnection(getConnection)
    try {
      block(connection)
    } finally {
      connection.close()
    }
  }

  def withTransaction[A](block: Connection => A): A = {
    withConnection { connection =>
      try {
        connection.setAutoCommit(false)
        val r = block(connection)
        connection.commit()
        r
      } catch {
        case e: ControlThrowable =>
          connection.commit(); throw e
        case NonFatal(e) => connection.rollback(); throw e
      }
    }
  }

  // shutdown

  def shutdown(): Unit = {
    closeDataSource(dataSource)
    deregisterDriver()
  }

  def deregisterDriver(): Unit = {
    DriverManager.deregisterDriver(driver)
  }

}

/**
 * Default implementation of the database API using a connection pool.
 */
class PooledDatabase(name: String, configuration: Configuration, classLoader: ClassLoader, pool: ConnectionPool)
    extends DefaultDatabase(name, configuration, classLoader) {

  def this(name: String, configuration: Configuration) = this(name, configuration, classOf[PooledDatabase].getClassLoader, new BoneConnectionPool)

  def createDataSource(): DataSource = pool.create(name, configuration, classLoader)

  def closeDataSource(dataSource: DataSource): Unit = pool.close(dataSource)

}
