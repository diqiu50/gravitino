/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

use crate::filesystem::{FileStat, FileSystemContext, RawFileSystem};
use fuse3::path::prelude::{ReplyData, ReplyOpen, ReplyStatFs, ReplyWrite};
use fuse3::path::Request;
use fuse3::raw::prelude::{
    FileAttr, ReplyAttr, ReplyCreated, ReplyDirectory, ReplyDirectoryPlus, ReplyEntry, ReplyInit,
};
use fuse3::raw::reply::{DirectoryEntry, DirectoryEntryPlus};
use fuse3::raw::Filesystem;
use fuse3::FileType::{Directory, RegularFile};
use fuse3::{Errno, FileType, Inode, SetAttr, Timestamp};
use futures_util::stream;
use futures_util::stream::BoxStream;
use futures_util::StreamExt;
use std::ffi::{OsStr, OsString};
use std::num::NonZeroU32;
use std::time::{Duration, SystemTime};

pub(crate) struct FuseApiHandle<T: RawFileSystem> {
    local_fs: T,
    default_ttl: Duration,
    fs_context: FileSystemContext,
}

impl<T: RawFileSystem> FuseApiHandle<T> {
    const DEFAULT_TTL: Duration = Duration::from_secs(1);
    const DEFAULT_MAX_WRITE: u32 = 16 * 1024;

    pub fn new(fs: T, context: FileSystemContext) -> Self {
        Self {
            local_fs: fs,
            default_ttl: Self::DEFAULT_TTL,
            fs_context: context,
        }
    }

    pub async fn get_file_path(&self, file_id: u64) -> String {
        self.local_fs.get_file_path(file_id).await
    }

    async fn get_modified_file_stat(
        &self,
        file_id: u64,
        size: Option<u64>,
        atime: Option<Timestamp>,
        mtime: Option<Timestamp>,
    ) -> Result<FileStat, Errno> {
        let mut file_stat = self.local_fs.stat(file_id).await?;

        if let Some(size) = size {
            file_stat.size = size;
        };

        if let Some(atime) = atime {
            file_stat.atime = atime;
        };

        if let Some(mtime) = mtime {
            file_stat.mtime = mtime;
        };

        Ok(file_stat)
    }
}

impl<T: RawFileSystem> Filesystem for FuseApiHandle<T> {
    async fn init(&self, _req: Request) -> fuse3::Result<ReplyInit> {
        self.local_fs.init().await;
        Ok(ReplyInit {
            max_write: NonZeroU32::new(Self::DEFAULT_MAX_WRITE).unwrap(),
        })
    }

    async fn destroy(&self, _req: Request) {
        //TODO need to call the destroy method of the local_fs
    }

    async fn lookup(
        &self,
        _req: Request,
        parent: Inode,
        name: &OsStr,
    ) -> fuse3::Result<ReplyEntry> {
        let name = name.to_string_lossy();
        let file_stat = self.local_fs.lookup(parent, &name).await?;
        Ok(ReplyEntry {
            ttl: self.default_ttl,
            attr: fstat_to_file_attr(&file_stat, &self.fs_context),
            generation: 0,
        })
    }

    async fn getattr(
        &self,
        _req: Request,
        inode: Inode,
        fh: Option<u64>,
        _flags: u32,
    ) -> fuse3::Result<ReplyAttr> {
        // check the opened file inode is the same as the inode
        if let Some(fh) = fh {
            self.local_fs.valid_file_id(inode, fh).await?;
        }

        let file_stat = self.local_fs.stat(inode).await?;
        Ok(ReplyAttr {
            ttl: self.default_ttl,
            attr: fstat_to_file_attr(&file_stat, &self.fs_context),
        })
    }

    async fn setattr(
        &self,
        _req: Request,
        inode: Inode,
        _fh: Option<u64>,
        set_attr: SetAttr,
    ) -> fuse3::Result<ReplyAttr> {
        let new_file_stat = self
            .get_modified_file_stat(inode, set_attr.size, set_attr.atime, set_attr.mtime)
            .await?;
        let attr = fstat_to_file_attr(&new_file_stat, &self.fs_context);
        self.local_fs.set_attr(inode, &new_file_stat).await?;
        Ok(ReplyAttr {
            ttl: self.default_ttl,
            attr: attr,
        })
    }

    async fn mkdir(
        &self,
        _req: Request,
        parent: Inode,
        name: &OsStr,
        _mode: u32,
        _umask: u32,
    ) -> fuse3::Result<ReplyEntry> {
        let name = name.to_string_lossy();
        let handle_id = self.local_fs.create_dir(parent, &name).await?;
        Ok(ReplyEntry {
            ttl: self.default_ttl,
            attr: dummy_file_attr(
                handle_id.file_id,
                Directory,
                Timestamp::from(SystemTime::now()),
                &self.fs_context,
            ),
            generation: 0,
        })
    }

    async fn unlink(&self, _req: Request, parent: Inode, name: &OsStr) -> fuse3::Result<()> {
        let name = name.to_string_lossy();
        self.local_fs.remove_file(parent, &name).await?;
        Ok(())
    }

    async fn rmdir(&self, _req: Request, parent: Inode, name: &OsStr) -> fuse3::Result<()> {
        let name = name.to_string_lossy();
        self.local_fs.remove_dir(parent, &name).await?;
        Ok(())
    }

    async fn open(&self, _req: Request, inode: Inode, flags: u32) -> fuse3::Result<ReplyOpen> {
        let file_handle = self.local_fs.open_file(inode, flags).await?;
        Ok(ReplyOpen {
            fh: file_handle.handle_id,
            flags: flags,
        })
    }

    async fn read(
        &self,
        _req: Request,
        inode: Inode,
        fh: u64,
        offset: u64,
        size: u32,
    ) -> fuse3::Result<ReplyData> {
        let data = self.local_fs.read(inode, fh, offset, size).await?;
        Ok(ReplyData { data: data })
    }

    async fn write(
        &self,
        _req: Request,
        inode: Inode,
        fh: u64,
        offset: u64,
        data: &[u8],
        _write_flags: u32,
        _flags: u32,
    ) -> fuse3::Result<ReplyWrite> {
        let written = self.local_fs.write(inode, fh, offset, data).await?;
        Ok(ReplyWrite { written: written })
    }

    async fn statfs(&self, _req: Request, _inode: Inode) -> fuse3::Result<ReplyStatFs> {
        //TODO: Implement statfs for the filesystem
        Ok(ReplyStatFs {
            blocks: 1000000,
            bfree: 1000000,
            bavail: 1000000,
            files: 1000000,
            ffree: 1000000,
            bsize: 4096,
            namelen: 255,
            frsize: 4096,
        })
    }

    async fn release(
        &self,
        _eq: Request,
        inode: Inode,
        fh: u64,
        _flags: u32,
        _lock_owner: u64,
        _flush: bool,
    ) -> fuse3::Result<()> {
        self.local_fs.close_file(inode, fh).await
    }

    async fn opendir(&self, _req: Request, inode: Inode, flags: u32) -> fuse3::Result<ReplyOpen> {
        let file_handle = self.local_fs.open_dir(inode, flags).await?;
        Ok(ReplyOpen {
            fh: file_handle.handle_id,
            flags: flags,
        })
    }

    type DirEntryStream<'a>
        = BoxStream<'a, fuse3::Result<DirectoryEntry>>
    where
        T: 'a;

    #[allow(clippy::needless_lifetimes)]
    async fn readdir<'a>(
        &'a self,
        _req: Request,
        parent: Inode,
        _fh: u64,
        offset: i64,
    ) -> fuse3::Result<ReplyDirectory<Self::DirEntryStream<'a>>> {
        let current = self.local_fs.stat(parent).await?;
        let files = self.local_fs.read_dir(parent).await?;
        let entries_stream =
            stream::iter(files.into_iter().enumerate().map(|(index, file_stat)| {
                Ok(DirectoryEntry {
                    inode: file_stat.file_id,
                    name: file_stat.name.clone().into(),
                    kind: file_stat.kind,
                    offset: (index + 3) as i64,
                })
            }));

        let relative_paths = stream::iter([
            Ok(DirectoryEntry {
                inode: current.file_id,
                name: ".".into(),
                kind: Directory,
                offset: 1,
            }),
            Ok(DirectoryEntry {
                inode: current.parent_file_id,
                name: "..".into(),
                kind: Directory,
                offset: 2,
            }),
        ]);

        let combined_stream = relative_paths.chain(entries_stream);
        Ok(ReplyDirectory {
            entries: combined_stream.skip(offset as usize).boxed(),
        })
    }

    async fn releasedir(
        &self,
        _req: Request,
        inode: Inode,
        fh: u64,
        _flags: u32,
    ) -> fuse3::Result<()> {
        self.local_fs.close_file(inode, fh).await
    }

    async fn create(
        &self,
        _req: Request,
        parent: Inode,
        name: &OsStr,
        _mode: u32,
        flags: u32,
    ) -> fuse3::Result<ReplyCreated> {
        let name = name.to_string_lossy();
        let file_handle = self.local_fs.create_file(parent, &name, flags).await?;
        Ok(ReplyCreated {
            ttl: self.default_ttl,
            attr: dummy_file_attr(
                file_handle.file_id,
                RegularFile,
                Timestamp::from(SystemTime::now()),
                &self.fs_context,
            ),
            generation: 0,
            fh: file_handle.handle_id,
            flags: flags,
        })
    }

    type DirEntryPlusStream<'a>
        = BoxStream<'a, fuse3::Result<DirectoryEntryPlus>>
    where
        T: 'a;

    #[allow(clippy::needless_lifetimes)]
    async fn readdirplus<'a>(
        &'a self,
        _req: Request,
        parent: Inode,
        _fh: u64,
        offset: u64,
        _lock_owner: u64,
    ) -> fuse3::Result<ReplyDirectoryPlus<Self::DirEntryPlusStream<'a>>> {
        let current = self.local_fs.stat(parent).await?;
        let files = self.local_fs.read_dir(parent).await?;
        let entries_stream =
            stream::iter(files.into_iter().enumerate().map(|(index, file_stat)| {
                Ok(DirectoryEntryPlus {
                    inode: file_stat.file_id,
                    name: file_stat.name.clone().into(),
                    kind: file_stat.kind,
                    offset: (index + 3) as i64,
                    attr: fstat_to_file_attr(&file_stat, &self.fs_context),
                    generation: 0,
                    entry_ttl: self.default_ttl,
                    attr_ttl: self.default_ttl,
                })
            }));

        let relative_paths = stream::iter([
            Ok(DirectoryEntryPlus {
                inode: current.file_id,
                name: OsString::from("."),
                kind: Directory,
                offset: 1,
                attr: fstat_to_file_attr(&current, &self.fs_context),
                generation: 0,
                entry_ttl: self.default_ttl,
                attr_ttl: self.default_ttl,
            }),
            Ok(DirectoryEntryPlus {
                inode: current.parent_file_id,
                name: OsString::from(".."),
                kind: Directory,
                offset: 2,
                attr: dummy_file_attr(
                    current.parent_file_id,
                    Directory,
                    Timestamp::from(SystemTime::now()),
                    &self.fs_context,
                ),
                generation: 0,
                entry_ttl: self.default_ttl,
                attr_ttl: self.default_ttl,
            }),
        ]);

        let combined_stream = relative_paths.chain(entries_stream);
        Ok(ReplyDirectoryPlus {
            entries: combined_stream.skip(offset as usize).boxed(),
        })
    }
}

const fn fstat_to_file_attr(file_st: &FileStat, context: &FileSystemContext) -> FileAttr {
    debug_assert!(file_st.file_id != 0 && file_st.parent_file_id != 0);
    FileAttr {
        ino: file_st.file_id,
        size: file_st.size,
        blocks: (file_st.size + context.block_size as u64 - 1) / context.block_size as u64,
        atime: file_st.atime,
        mtime: file_st.mtime,
        ctime: file_st.ctime,
        kind: file_st.kind,
        perm: file_st.perm,
        nlink: file_st.nlink,
        uid: context.uid,
        gid: context.gid,
        rdev: 0,
        blksize: context.block_size,
        #[cfg(target_os = "macos")]
        crtime: file_st.ctime,
        #[cfg(target_os = "macos")]
        flags: 0,
    }
}

const fn dummy_file_attr(
    file_id: u64,
    kind: FileType,
    now: Timestamp,
    context: &FileSystemContext,
) -> FileAttr {
    debug_assert!(file_id != 0);
    let mode = match kind {
        Directory => context.default_dir_perm,
        _ => context.default_file_perm,
    };
    FileAttr {
        ino: file_id,
        size: 0,
        blocks: 1,
        atime: now,
        mtime: now,
        ctime: now,
        kind,
        perm: mode,
        nlink: 0,
        uid: context.uid,
        gid: context.gid,
        rdev: 0,
        blksize: context.block_size,
        #[cfg(target_os = "macos")]
        crtime: now,
        #[cfg(target_os = "macos")]
        flags: 0,
    }
}