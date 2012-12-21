/*
 * Copyright (c) 2012 Roberto Tyley
 *
 * This file is part of 'BFG Repo-Cleaner' - a tool for removing large
 * or troublesome blobs from Git repositories.
 *
 * BFG Repo-Cleaner is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * BFG Repo-Cleaner is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses/ .
 */

package com.madgag.git.bfg

import cleaner.{RepoRewriter, BlobReplacer}
import org.eclipse.jgit.lib._
import org.eclipse.jgit.storage.file.{WindowCacheConfig, WindowCache, FileRepository}
import org.eclipse.jgit.util.FS
import java.io.File
import scala.Some
import scopt.immutable.OptionParser
import System.nanoTime
import GitUtil._

case class CMDConfig(stripBiggestBlobs: Option[Int] = None,
                     stripBlobsBiggerThan: Option[Int] = None,
                     protectBlobsFromRevisions: Set[String] = Set("HEAD"),
                     gitdir: Option[File] = None)

object Main extends App {

  val wcConfig: WindowCacheConfig = new WindowCacheConfig()
  wcConfig.setStreamFileThreshold(1024 * 1024)
  WindowCache.reconfigure(wcConfig)

  val parser = new OptionParser[CMDConfig]("bfg") {
    def options = Seq(
      opt("b", "strip-blobs-bigger-than", "<size>", "strip blobs bigger than X") {
        (v: String, c: CMDConfig) => c.copy(stripBlobsBiggerThan = Some(byteSizeFrom(v)))
      },
      intOpt("B", "strip-biggest-blobs", "NUM", "strip the top NUM biggest blobs") {
        (v: Int, c: CMDConfig) => c.copy(stripBiggestBlobs = Some(v))
      },
      opt("p", "protect-blobs-from", "<refs>", "protect blobs that appear in the most recent versions of the specified refs") {
        (v: String, c: CMDConfig) => c.copy(protectBlobsFromRevisions = v.split(',').toSet)
      },
      arg("<repo>", "repo to clean") {
        (v: String, c: CMDConfig) =>
          val dir = new File(v).getCanonicalFile
          val gitdir = RepositoryCache.FileKey.resolve(dir, FS.detect())
          if (gitdir == null || !gitdir.exists)
            throw new IllegalArgumentException("'%s' is not a valid Git repository.".format(dir.getAbsolutePath))
          c.copy(gitdir = Some(gitdir))
      }
    )

    def byteSizeFrom(v: String): Int = {
      val magnitudeChars = List('B', 'K', 'M', 'G')
      magnitudeChars.indexOf(v.takeRight(1)(0)) match {
        case -1 => v.toInt
        case index => v.dropRight(1).toInt << (index * 10)
      }
    }
  }

  parser.parse(args, CMDConfig()) map {
    config =>
      println(config)

      implicit val repo = new FileRepository(config.gitdir.get)

      println("Using repo : " + repo.getDirectory.getAbsolutePath)

      implicit val codec = scalax.io.Codec.UTF8

      //      def getBadBlobsFromAdjacentFile(repo: FileRepository): Set[ObjectId] = {
      //        Path.fromString(repo.getDirectory.getAbsolutePath + ".bad").lines().map(line => ObjectId.fromString(line.split(' ')(0))).toSet
      //      }

      val protectedBlobIds: Set[ObjectId] = allBlobsReachableFrom(config.protectBlobsFromRevisions)

      println("Found " + protectedBlobIds.size + " blobs to protect")

      val start = nanoTime
      val badIds = {
        biggestBlobs(repo).filterNot(o => protectedBlobIds(o.objectId)).take(config.stripBiggestBlobs.get).map(_.objectId).toSet
      } // getBadBlobsFromAdjacentFile(repo)
      val end = nanoTime

      println("Blob-targeting pack-scan duration = %.3f".format((end - start) / 1.0e9))

      println("Found " + badIds.size + " blob ids to remove")

      //val eliminateableBlobIds = badIds -- protectedBlobIds

      //println("badIdsExcludingProtectedIds size = " + eliminateableBlobIds.size)

      RepoRewriter.rewrite(repo, new BlobReplacer(badIds))
  }

}