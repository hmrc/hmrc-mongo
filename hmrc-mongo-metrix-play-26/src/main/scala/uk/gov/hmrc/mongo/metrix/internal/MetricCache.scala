/*
 * Copyright 2019 HM Revenue & Customs
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

package uk.gov.hmrc.mongo.metrix.internal

import java.util.concurrent.ConcurrentHashMap

import uk.gov.hmrc.mongo.metrix.PersistedMetric

import scala.collection.convert.decorateAsScala._

class MetricCache {

  private val cache = new ConcurrentHashMap[String, Int]().asScala

  def refreshWith(allMetrics: List[PersistedMetric]): Unit = synchronized {
    allMetrics.foreach(m => cache.put(m.name, m.count))
    val asMap: Map[String, Int] = allMetrics.map(m => m.name -> m.count).toMap
    cache.keys.foreach(key => if (!asMap.contains(key)) cache.remove(key))
  }

  def valueOf(name: String): Int = cache.getOrElse(name, 0)
}