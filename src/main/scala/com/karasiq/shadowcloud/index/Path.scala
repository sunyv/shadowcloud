package com.karasiq.shadowcloud.index

import scala.language.{implicitConversions, postfixOps}

case class Path(nodes: Seq[String]) {
  def isRoot: Boolean = {
    nodes.isEmpty
  }

  def /(node: String): Path = {
    if (node.nonEmpty) copy(nodes :+ node) else this
  }

  def parent: Path = {
    if (nodes.nonEmpty) copy(nodes.dropRight(1)) else this
  }

  def name: String = {
    if (nodes.nonEmpty) nodes.last else "/"
  }

  override def toString: String = {
    nodes.mkString("/", "/", "")
  }
}

object Path {
  val root = Path(Nil)

  implicit def fromString(str: String): Path = {
    val nodes: Seq[String] = str.split(Array('/', '\\')).filter(_.nonEmpty)
    if (nodes.nonEmpty) Path(nodes) else root
  }
}

trait HasPath {
  def path: Path
}