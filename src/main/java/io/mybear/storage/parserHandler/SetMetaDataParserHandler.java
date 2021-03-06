package io.mybear.storage.parserHandler;

import io.mybear.storage.StorageDio;
import io.mybear.storage.storageNio.StorageClientInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

/**
 * Created by jamie on 2017/7/12.
 */
public class SetMetaDataParserHandler implements ParserHandler<StorageClientInfo, ByteBuffer> {
    public static final int SIZE = 32;
    private static final Logger LOGGER = LoggerFactory.getLogger(SetMetaDataParserHandler.class);

    @Override
    public long handleMetaData(StorageClientInfo con, ByteBuffer nioData) {
        long filenameLen = nioData.getLong(0);
        long meta_buff_length = nioData.getLong(8);
        byte flag = nioData.get(16);
        byte[] group_name = new byte[16];
        nioData.position(17);
        nioData.get(group_name, 0, 16);
        return meta_buff_length;
    }

    @Override
    public void handle(StorageClientInfo con, ByteBuffer nioData) {

    }

    @Override
    public void handleEnd(StorageClientInfo con, ByteBuffer nioData) {
        StorageDio.queuePush(con);
    }

    @Override
    public int getSize() {
        return SIZE;
    }

}
