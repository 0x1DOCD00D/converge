ThisBuild / scalaVersion := "3.8.4"
ThisBuild / organization := "org.converge"
ThisBuild / version := "0.4.0"

lazy val root = (project in file("."))
  .settings(
    name := "converge",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "ujson" % "4.4.3"
    ),
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked"
    ),
    Compile / mainClass := Some("converge.Main")
  )
