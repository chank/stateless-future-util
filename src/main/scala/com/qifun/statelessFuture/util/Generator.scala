/*
 * stateless-future-util
 * Copyright 2014 深圳岂凡网络有限公司 (Shenzhen QiFun Network Corp., LTD)
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

package com.qifun.statelessFuture
package util

import scala.collection.LinearSeqOptimized
import scala.collection.immutable.LinearSeq
import scala.collection.generic.GenericTraversableTemplate
import scala.collection.generic.SeqFactory
import scala.util.control.TailCalls._
import scala.util.control.Exception.Catcher
import scala.collection.mutable.LazyBuilder
import scala.collection.mutable.ListBuffer
import scala.language.implicitConversions

object Generator {

  final object Seq extends SeqFactory[Generator.Seq] {

    override final def apply[Element](elements: Element*): Generator.Seq[Element] = {
      runtimeCollectionToFuture(elements)
    }

    final def apply[Element](elements: TraversableOnce[Element]): Generator.Seq[Element] = {
      runtimeCollectionToFuture(elements)
    }

    override final def newBuilder[Element] = new LazyBuilder[Element, Generator.Seq[Element]] {

      private def toFuture(parts: ListBuffer[TraversableOnce[Element]]): Generator[Element]#Future[Unit] = {
        parts.headOption match {
          case Some(head) => {
            Generator[Element].Future {
              Generator.runtimeCollectionToFuture(head).await
              toFuture(parts.tail).await
            }
          }
          case None => {
            Generator[Element].Future(())
          }
        }
      }

      override final def result = {
        val parts = this.parts
        this.parts = null
        toFuture(parts)
      }

    }

    private[Generator] final case object Empty extends Generator.Seq[Nothing] {

      override final def isEmpty: Boolean = true

      override final def head: Nothing = {
        throw new NoSuchElementException("head of empty list")
      }

      override final def tail: Nothing = {
        throw new UnsupportedOperationException("tail of empty list")
      }

    }

    private[Generator] final case class NonEmpty[+Element](
      override val head: Element,
      val continue: Unit => TailRec[Generator.Seq[Element]]) extends Generator.Seq[Element] {

      override final def isEmpty: Boolean = false

      override final def tail: Generator.Seq[Element] = continue(()).result

    }

  }

  sealed abstract class Seq[+Element] extends scala.collection.immutable.Seq[Element]
    with LinearSeq[Element]
    with GenericTraversableTemplate[Element, Generator.Seq]
    with LinearSeqOptimized[Element, Generator.Seq[Element]] {

    override final def companion = Generator.Seq

  }

  import scala.language.implicitConversions

  @inline
  implicit final def futureToGeneratorSeq[U, Element](future: Generator[Element]#Future[U]): Generator[Element]#OutputSeq = {
    future.onComplete { u => done(Generator.Seq.Empty) }(PartialFunction.empty).result
  }

  private def indexedSeqToFuture[Element](seq: scala.collection.IndexedSeq[Element]): Awaitable.Stateless[Unit, Generator.Seq[Element]] = {
    def indexedSeqToFuture(i: Int): Awaitable.Stateless[Unit, Generator.Seq[Element]] = {
      Generator[Element].Future {
        if (i < seq.length) {
          Generator[Element].apply(seq(i)).await
          indexedSeqToFuture(i + 1).await
        }
      }
    }
    indexedSeqToFuture(0)
  }

  private def linearSeqToFuture[Element](seq: scala.collection.LinearSeq[Element]): Awaitable.Stateless[Unit, Generator.Seq[Element]] = Generator[Element].Future {
    if (seq.nonEmpty) {
      Generator[Element].apply(seq.head).await
      linearSeqToFuture(seq.tail).await
    }
  }

  private def iteratorToFuture[Element](i: Iterator[Element]): Awaitable.Stateless[Unit, Generator.Seq[Element]] = {
    // 为了给当前环境加锁，创建一个Upvalue对象
    // 当多个线程并发调用onComplete的时候，upvalueIterator就不会坏掉。
    object Upvalue { upvalue =>
      var upvalueIterator = i
      def future = Generator[Element].Future {
        val d2 = upvalue.synchronized {
          val (d1, d2) = upvalueIterator.duplicate
          upvalueIterator = d1
          d2
        }
        if (d2.hasNext) {
          Generator[Element].apply(d2.next()).await
          iteratorToFuture(d2).await
        }
      }
    }
    Upvalue.future
  }

  @inline
  private def iterableToFuture[Element, Origin](iterable: Iterable[Element]): Awaitable.Stateless[Unit, Generator.Seq[Element]] = {
    iteratorToFuture(iterable.iterator)
  }

  @inline
  private def runtimeCollectionToFuture[Element](elements: TraversableOnce[Element]): Awaitable.Stateless[Unit, Generator.Seq[Element]] = {
    elements match {
      case seq: LinearSeq[Element] => linearSeqToFuture(seq)
      case seq: IndexedSeq[Element] => indexedSeqToFuture(seq)
      case iterable: Iterable[Element] => iterableToFuture(iterable)
      case other => iteratorToFuture(other.toIterator)
    }
  }

}

final case class Generator[Element]() extends (Element => Awaitable[Unit, Generator.Seq[Element]]) {

  type OutputSeq = Generator.Seq[Element]

  object Future extends AwaitableFactory[OutputSeq] {
    type Stateful[AwaitResult] = Future[AwaitResult] with Awaitable.Stateful[AwaitResult, OutputSeq]
    type Stateless[AwaitResult] = Future[AwaitResult] with Awaitable.Stateless[AwaitResult, OutputSeq]
  }

  type Future[AwaitResult] = Awaitable[AwaitResult, OutputSeq]

  private type FutureSeq[A] = AwaitableSeq[A, Generator.Seq[Element]]

  final def futureSeq[A](underlying: LinearSeq[A]) = new FutureSeq[A](underlying)

  final def futureSeq[A](underlying: TraversableOnce[A]) = new FutureSeq[A](Generator.Seq(underlying))

  private[Generator]type Stateless[AwaitResult] = Awaitable.Stateless[AwaitResult, OutputSeq]

  @inline
  final def apply(element: Element): Future.Stateless[Unit] = new Awaitable.Stateless[Unit, OutputSeq] {
    private type TailRecResult = OutputSeq
    private type AwaitResult = Unit
    override final def onComplete(handler: AwaitResult => TailRec[TailRecResult])(implicit catcher: Catcher[TailRec[TailRecResult]]): TailRec[TailRecResult] = {
      done(Generator.Seq.NonEmpty(element, handler))
    }
  }

  @inline
  final def apply(elements: Element*): Future.Stateless[Unit] = Generator.runtimeCollectionToFuture(elements)

}
