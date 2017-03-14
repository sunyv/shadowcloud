package com.karasiq.shadowcloud.storage

import akka.stream.scaladsl.Source

import scala.collection.immutable.TreeSet
import scala.language.postfixOps

trait SeqRepository[Key] extends Repository[Key] {
  def sortedKeys(implicit ord: Ordering[Key]): Source[Key, Result] = {
    keys.fold(TreeSet.empty[Key])(_ + _).mapConcat(identity)
  }

  def keysBefore(id: Key)(implicit ord: Ordering[Key]): Source[Key, Result] = {
    keys.filter(ord.lt(_, id))
  }

  def keysAfter(id: Key)(implicit ord: Ordering[Key]): Source[Key, Result] = {
    keys.filter(ord.gt(_, id))
  }
}