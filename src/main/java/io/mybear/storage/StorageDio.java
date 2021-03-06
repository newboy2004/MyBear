package io.mybear.storage;

import io.mybear.common.*;
import io.mybear.storage.storageNio.Connection;
import io.mybear.storage.storageNio.StorageClientInfo;
import io.mybear.storage.storageNio.TimeUtil;
import io.mybear.storage.trunkMgr.TrunkShared;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.CRC32;

import static io.mybear.storage.StorageGlobal.g_disk_reader_threads;
import static io.mybear.storage.StorageGlobal.g_disk_writer_threads;
import static io.mybear.storage.trunkMgr.TrunkShared.FDFS_TRUNK_FILE_ALLOC_SIZE_OFFSET;
import static io.mybear.storage.trunkMgr.TrunkShared.FDFS_TRUNK_FILE_TYPE_REGULAR;

/**
 * Created by jamie on 2017/6/21.
 */
public class StorageDio {
    public static final int _FILE_TYPE_APPENDER = 1;
    public static final int FILE_TYPE_TRUNK = 2; //if trunk file, since V3.0
    public static final int _FILE_TYPE_SLAVE = 4;
    public static final int _FILE_TYPE_REGULAR = 8;
    public static final int _FILE_TYPE_LINK = 16;

    private static final Logger LOGGER = LoggerFactory.getLogger(StorageDio.class);
    public static Object g_dio_thread_lock = new Object();
    public static ExecutorService[] g_dio_contexts;
    public static StorageDioThreadData[] g_dio_thread_data;
    public static boolean g_disk_rw_separated = false;
    CRC32 crc32 = new CRC32();

    public static void init() {
        g_dio_thread_lock = new Object();
        int threads_count_per_path = g_disk_reader_threads + g_disk_writer_threads;
        FdfsStorePaths paths = TrunkShared.getFdfsStorePaths();
        int pathConut = paths.getCount();
        int context_count = threads_count_per_path * pathConut;
        g_dio_contexts = new ExecutorService[context_count];
        for (int i = 0; i < g_dio_contexts.length; i++) {
            // ArrayBlockingQueue
            g_dio_contexts[i] = Executors.newSingleThreadExecutor();
        }
        g_dio_thread_data = new StorageDioThreadData[pathConut];
        for (int i = 0; i < g_dio_thread_data.length; i++) {
            StorageDioThreadData threadData = g_dio_thread_data[i] = new StorageDioThreadData();
            threadData.count = threads_count_per_path;
            threadData.contexts = Arrays.copyOfRange(g_dio_contexts, i, i + g_disk_reader_threads);
            threadData.reader = threadData.contexts;
            threadData.writer = Arrays.copyOfRange(g_dio_contexts, i + g_disk_reader_threads, i + g_disk_reader_threads + g_disk_writer_threads);
        }
    }

    public static ExecutorService getThreadIndex(StorageClientInfo pTask, final int store_path_index, final int file_op) {
        ExecutorService[] contexts;
        int count;
        StorageDioThreadData pThreadData = g_dio_thread_data[store_path_index];
        if (g_disk_rw_separated) {
            if (file_op == FDFS_TRUNK_FILE_ALLOC_SIZE_OFFSET) {
                contexts = pThreadData.reader;
                count = g_disk_reader_threads;
            } else {
                contexts = pThreadData.writer;
                count = g_disk_writer_threads;
            }
        } else {
            contexts = pThreadData.contexts;
            count = contexts.length;
        }
        return contexts[pTask.hashCode() % count];
    }

    public static boolean queuePush(Connection pTask) {
        g_dio_contexts[0].execute((StorageClientInfo) pTask);
        return true;
    }


    public static int dio_read_file(StorageClientInfo pTask) {
        StorageFileContext pFileContext = pTask.fileContext;
        FileChannel channel = pFileContext.fileChannel;
        if (!channel.isOpen()) return 0;
        int result = 0;
        long end = 0;
        do {
            try {
                //dio_open_file(pFileContext);
                end = pFileContext.end;
                long got = channel.transferTo(pFileContext.offset, end - pFileContext.offset, pTask.getChannel());
                if (got == -1) {
                    pFileContext.fileChannel.close();
                    return -1;
                } else if (got == 0 && pTask.isIdleTimeout()) {
                    pFileContext.fileChannel.close();
                    pTask.close("超时");
                    return -1;
                }
                if (got != 0) {
                    pTask.setLastWriteTime(TimeUtil.currentTimeMillis());
                    pFileContext.offset += got;
                } else {
//                    System.out.println("hhhh");
                }
                nioNotify(pTask);
            } catch (IOException e) {
                result = -1;
                e.printStackTrace();
                LOGGER.error("");
            }
//            synchronized (g_dio_thread_lock) {
//                ++g_storage_stat.total_file_read_count;
//                if (result == 0) ++g_storage_stat.success_file_read_count;
//            }
        } while (false);

        if (pTask.fileContext.offset == end) {
            // pTask.flagData = Boolean.FALSE;
            try {
                pFileContext.fileChannel.close();
                if (pFileContext.done_callback != null) {
                    pFileContext.done_callback.accept(pTask);
                }
                pTask.close("任务完成");
            } catch (IOException e) {
                e.printStackTrace();
                result = -1;
            }
        }

        return result;

    }

    public static void nioNotify(StorageClientInfo pTask) {
        pTask.flagData = Boolean.TRUE;

    }

//
//    public static int dio_write_file(StorageClientInfo pTask) {
//        if (!pTask.getChannel().isOpen()) return -1;
//        StorageFileContext pFileContext = pTask.fileContext;
//        int result = 0;
//        FileChannel channel = pFileContext.fileChannel;
//        StorageUploadInfo uploadInfo = (StorageUploadInfo) pFileContext.extra_info;
//        long end = pFileContext.end;
//        do {
//            if (channel == null || !channel.isOpen()) {
//                FileBeforeOpenCallback callback = uploadInfo.getBeforeOpenCallback();
//                if (callback != null) {
//                    callback.accept(pTask);
//                }
//            }
//            try {
//                dio_open_file(pFileContext);
//                channel = pFileContext.fileChannel;
//                pFileContext.offset += channel.transferFrom(pTask.getChannel(), pFileContext.offset, end - pFileContext.offset);
//            } catch (IOException e) {
//                result = -1;
//                e.printStackTrace();
//                LOGGER.error("");
//            }
//            synchronized (g_dio_thread_lock) {
////                g_storage_stat.total_file_write_count++;
////                if (result == 0) {
////                    g_storage_stat.success_file_write_count++;
////                }
//            }
//            if (result != 0) break;
//        } while (false);
//        if (pFileContext.offset < end) {
////            pTask.enableWrite(false);
////StorageDio.queuePush(pTask);
//            nioNotify(pTask);
//            LOGGER.debug("切换写");
//        } else {
//            try {
//                LOGGER.info("任务完成");
//                pFileContext.fileChannel.close();
//                if (pFileContext.done_callback != null)
//                    pFileContext.done_callback.accept(pTask);
//            } catch (IOException e) {
//                e.printStackTrace();
//                result = -1;
//            }
//        }
//
//        return result;
//    }

    public static int dio_write_file(StorageClientInfo pTask) {
        if (!pTask.getChannel().isOpen()) return -1;
        StorageFileContext pFileContext = pTask.fileContext;
        int result = 0;
        FileChannel channel = pFileContext.fileChannel;
        StorageUploadInfo uploadInfo = (StorageUploadInfo) pFileContext.extra_info;
        long end = pFileContext.end;
        do {
            if (channel == null || !channel.isOpen()) {
                FileBeforeOpenCallback callback = uploadInfo.getBeforeOpenCallback();
                if (callback != null) {
                    callback.accept(pTask);
                }
            }
            try {
                dio_open_file(pFileContext);
                channel = pFileContext.fileChannel;
                pTask.readBuffer.flip();
                pFileContext.offset += channel.write(pTask.readBuffer);
            } catch (IOException e) {
                result = -1;
                e.printStackTrace();
                LOGGER.error("");
            }
            synchronized (g_dio_thread_lock) {
//                g_storage_stat.total_file_write_count++;
//                if (result == 0) {
//                    g_storage_stat.success_file_write_count++;
//                }
            }
            if (result != 0) break;
        } while (false);
        if (pFileContext.offset < end) {
//            pTask.enableWrite(false);
//StorageDio.queuePush(pTask);
            nioNotify(pTask);
            // LOGGER.debug("切换写");
        } else {
            try {
                LOGGER.info("任务完成");
                pFileContext.fileChannel.close();
                pFileContext.randomAccessFile.close();
                if (pFileContext.done_callback != null)
                    pFileContext.done_callback.accept(pTask);
            } catch (IOException e) {
                e.printStackTrace();
                result = -1;
            }
        }

        return result;
    }

    public static void dio_open_file(StorageFileContext pFileContext) throws IOException {
        try {
            FileChannel fileChannel = pFileContext.fileChannel;
            if (fileChannel == null) {
                File file = new File(pFileContext.filename);
                file.createNewFile();
                pFileContext.randomAccessFile = new RandomAccessFile(pFileContext.filename, "rw");
                pFileContext.fileChannel = pFileContext.randomAccessFile.getChannel();
                if (pFileContext.offset > 0) {
                    fileChannel.position(pFileContext.offset);
                }
            }
        } catch (IOException e) {
//            synchronized (g_dio_thread_lock) {
//                g_storage_stat.total_file_open_count += 2;
//            }
            throw e;
        }
//
//        synchronized (g_dio_thread_lock) {
//            g_storage_stat.total_file_open_count += 1;
//        }
    }

    public void terminate() {
        g_dio_contexts = null;
    }

    /**
     * 之后还要修改
     *
     * @param pTask
     * @param fileContext
     * @return
     */
    public void dio_truncate_file(StorageClientInfo pTask, StorageFileContext fileContext) throws IOException {
        try {
            do {
                StorageUploadInfo uploadInfo = ((StorageUploadInfo) fileContext.extra_info);
                if (!fileContext.fileChannel.isOpen()) {
                    FileBeforeOpenCallback callBack = uploadInfo.getBeforeOpenCallback();
                    if (callBack != null) {
                        callBack.accept(pTask);
                    }
                    dio_open_file(fileContext);
                }
                fileContext.fileChannel.truncate(fileContext.offset);
                if (uploadInfo.getBeforeCloseCallback() != null) {
                    uploadInfo.getBeforeCloseCallback().accept(pTask);
                }

	/* file write done, close it */
                fileContext.fileChannel.close();
                fileContext.fileChannel = null;
                if (fileContext.done_callback == null) {
                    fileContext.done_callback.accept(pTask);
                }
                return;
            } while (false);
        } catch (IOException e) {
            e.printStackTrace();
            //result = -1;
        }
//        pTask.cx
//        pClientInfo -> clean_func(pTask);

        if (fileContext.done_callback != null) {
            fileContext.done_callback.accept(pTask);
        }
    }

    public void dio_delete_normal_file(StorageClientInfo StorageClientInfo) {
        StorageFileContext fileContext = StorageClientInfo.fileContext;
        if (!new File(fileContext.filename).delete()) {
            fileContext.log_callback.accept(StorageClientInfo);
        }
        fileContext.done_callback.accept(StorageClientInfo);
    }

    public int dio_delete_trunk_file(StorageClientInfo pTask) {
        return 0;
    }

    public int dio_discard_file(StorageClientInfo pTask) {


        return 0;
    }

    public void dio_read_finish_clean_up(StorageClientInfo pTask) {

    }

    public void dio_write_finish_clean_up(StorageClientInfo pTask) {

    }

    public void dio_append_finish_clean_up(StorageClientInfo pTask) {

    }

    public void dio_trunk_write_finish_clean_up(StorageClientInfo pTask) {

    }

    public void dio_modify_finish_clean_up(StorageClientInfo pTask) {

    }

//
//    public int dio_check_trunk_file_ex(int fd, String filename, final long offset) {
//
//    }
//
//    public int dio_check_trunk_file_when_upload(StorageClientInfo pTask) {
//
//    }
//
//    public int dio_check_trunk_file_when_sync(StorageClientInfo pTask) {
//
//    }

    public int dio_write_chunk_header(StorageClientInfo pTask) {
        StorageFileContext fileContext = pTask.fileContext;
        StorageUploadInfo uploadInfo = (StorageUploadInfo) fileContext.extra_info;
//        FDFSTrunkHeader trunkHeader = new FDFSTrunkHeader();
        ByteBuffer trunkHeader = ByteBuffer.allocate(17 + 6 + 1);
        if ((uploadInfo.getFileType() & _FILE_TYPE_LINK) > 0) {
            trunkHeader.put(TrunkShared.FDFS_TRUNK_FILE_TYPE_LINK);
        } else {
            trunkHeader.put(FDFS_TRUNK_FILE_TYPE_REGULAR);
        }
        trunkHeader.putInt(uploadInfo.getTrunkInfo().getFile().getSize());
        trunkHeader.putInt((int) (fileContext.end - fileContext.start));
        trunkHeader.putInt((int) fileContext.crc32);
        trunkHeader.putInt(uploadInfo.getStartTime());
//        char[]  uploadInfo.getFileExtName();
//        for (int i = 0; i < ; i++) {
//            trunkHeader.putChar();
//        }

        try {
            fileContext.fileChannel.position(fileContext.start);
        } catch (IOException e) {
//            result = errno != 0 ? errno : EIO;
//            logError("file: "__FILE__", line: %d, " \
//                    "lseek file: %s fail, " \
//                    "errno: %d, error info: %s", \
//                    __LINE__, pFileContext->filename, \
//                    result, STRERROR(result));
//            return result;
        }
        try {
            fileContext.fileChannel.write(trunkHeader);
        } catch (IOException e) {
//            result = errno != 0 ? errno : EIO;
//            logError("file: "__FILE__", line: %d, " \
//                    "write to file: %s fail, " \
//                    "errno: %d, error info: %s", \
//                    __LINE__, pFileContext->filename, \
//                    result, STRERROR(result));
//            return result;
        }
        return 0;


    }

}
