package com.karasiq.shadowcloud.index

import com.karasiq.shadowcloud.index.diffs.FolderDiff
import com.karasiq.shadowcloud.index.utils.{HasEmpty, HasPath, HasWithoutData, Mergeable}
import com.karasiq.shadowcloud.utils.Utils

import scala.collection.GenTraversableOnce
import scala.language.postfixOps

case class Folder(path: Path, created: Long = 0, lastModified: Long = 0,
                  folders: Set[String] = Set.empty, files: Set[File] = Set.empty)
  extends HasPath with HasEmpty with HasWithoutData with Mergeable {

  type Repr = Folder
  type DiffRepr = FolderDiff
  require(lastModified >= created, "Invalid folder time")
  require(files.forall(_.path.parent == this.path), "Invalid file paths")

  def addFiles(files: GenTraversableOnce[File]): Folder = {
    val newFiles = this.files ++ files
    copy(lastModified = Utils.timestamp, files = newFiles)
  }

  def addFolders(folders: GenTraversableOnce[String]): Folder = {
    copy(lastModified = Utils.timestamp, folders = this.folders ++ folders)
  }

  def addFiles(files: File*): Folder = {
    addFiles(files)
  }

  def addFolders(folders: String*): Folder = {
    addFolders(folders)
  }

  def deleteFolders(folders: GenTraversableOnce[String]): Folder = {
    copy(lastModified = Utils.timestamp, folders = this.folders -- folders)
  }

  def deleteFiles(files: GenTraversableOnce[File]): Folder = {
    copy(lastModified = Utils.timestamp, files = this.files -- files)
  }

  def deleteFolders(folders: String*): Folder = {
    deleteFolders(folders)
  }

  def deleteFiles(files: File*): Folder = {
    deleteFiles(files)
  }

  def merge(folder: Folder): Folder = {
    require(path == folder.path, "Invalid path")
    addFolders(folder.folders).addFiles(folder.files)
  }

  def diff(oldFolder: Folder): FolderDiff = {
    require(path == oldFolder.path, "Invalid path")
    FolderDiff(oldFolder, this)
  }

  def patch(diff: FolderDiff): Folder = {
    this
      .deleteFiles(diff.deletedFiles)
      .addFiles(diff.newFiles)
      .deleteFolders(diff.deletedFolders)
      .addFolders(diff.newFolders)
      .copy(lastModified = math.max(lastModified, diff.time))
  }

  def withPath(newPath: Path): Folder = {
    copy(path = newPath, files = files.map(file ⇒ file.copy(path = file.path.move(newPath))))
  }

  def isEmpty: Boolean = {
    files.isEmpty && folders.isEmpty
  }

  def withoutData: Folder = {
    copy(files = files.map(_.withoutData))
  }

  override def hashCode(): Int = {
    (path, folders, files).hashCode()
  }

  override def equals(obj: scala.Any): Boolean = obj match {
    case f: Folder ⇒
      f.path == path && f.folders == folders && f.files == files

    case _ ⇒
      false 
  }

  override def toString: String = {
    s"Folder($path, folders: [${folders.mkString(", ")}], files: [${files.mkString(", ")}])"
  }
}