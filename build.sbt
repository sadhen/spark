/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

scalaVersion in ThisBuild := "2.12.8"

transitiveClassifiers in Global := Seq(Artifact.SourceClassifier)

lazy val root = (project in file("."))
  .settings(commonSettings)
  .settings(
    name := "spark-parent",
  )

lazy val tags = (project in file("common/tags"))
  .settings(commonSettings)
  .settings(
    name := "spark-tags"
  ).dependsOn(root)

lazy val launcher = (project in file("launcher"))
  .settings(commonSettings)
  .settings(
    crossPaths := false,
    name := "spark-launcher",
  ).dependsOn(tags)

lazy val kvstore = (project in file("common/kvstore"))
  .settings(commonSettings)
  .settings(
    crossPaths := false,
    name := "spark-kvstore",
  ).dependsOn(tags)

lazy val network_common = (project in file("common/network-common"))
  .settings(commonSettings)
  .settings(
    crossPaths := false,
    name := "spark-network-common",
  ).dependsOn(tags)

lazy val unsafe = (project in file("common/unsafe"))
  .settings(commonSettings)
  .settings(
    crossPaths := false,
    name := "spark-unsafe",
  ).dependsOn(tags)

lazy val network_shuffle = (project in file("common/network-shuffle"))
  .settings(commonSettings)
  .settings(
    name := "spark-network-shuffle",
  ).dependsOn(network_common)


lazy val core = (project in file("core"))
  .settings(commonSettings)
  .settings(
    name := "spark-core",
  ).dependsOn(network_shuffle, launcher, kvstore, unsafe)

lazy val commonSettings = Seq(
  organization := "org.apache.spark",
  version := "3.0.0-SNAPSHOT",
  conflictWarning := conflictWarning.value.copy(failOnConflict = false),
  externalPom(Def.setting(baseDirectory.value / "pom.xml")),
  updateOptions := updateOptions.value.withLatestSnapshots(false),
)
