/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.store.index;

import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.logging.InternalLoggerFactory;
import org.apache.rocketmq.store.MappedFile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.List;

/**
 * Header  +solr talbe   index linked list
 *  40b       500w*4b       2000W*20
 * 文件名fileName是以创建时的时间戳命名的，文件大小是固定的，等于40+500W*4+2000W*20= 420000040个字节大小。
 *
 * Index Header结构各字段的含义：
 * beginTimestamp：第一个索引消息落在Broker的时间戳；
 * endTimestamp：最后一个索引消息落在Broker的时间戳；
 * beginPhyOffset：第一个索引消息在commitlog的偏移量；
 * endPhyOffset：最后一个索引消息在commitlog的偏移量；
 * hashSlotCount：构建索引占用的槽位数；
 * indexCount：构建的索引个数；
 *
 * IndexFile作用
 * MessageStore中存储的消息除了通过ConsumeQueue提供给consumer消费之外，
 * 还支持通过MessageID或者MessageKey来查询消息。使用ID查询时，
 * 因为ID就是用broker+offset生成的，所以很容易就找到对应的commitLog文件来读取消息。
 * 对于用MessageKey来查询消息，MessageStore通过构建一个index来提高读取速度。
 *
 *
 *整个slotTable+indexLinkedList可以理解成java的HashMap。每当放一个新的消息的index进来，
 * 首先取MessageKey的hashCode，然后用hashCode对slot总数取模，得到应该放到哪个slot中，slot总数系统默认500W个。
 *
 */
public class IndexFile {
    private static final InternalLogger log = InternalLoggerFactory.getLogger(LoggerName.STORE_LOGGER_NAME);
    private static int hashSlotSize = 4;
    private static int indexSize = 20;
    private static int invalidIndex = 0;
    private final int hashSlotNum;
    private final int indexNum;
    private final MappedFile mappedFile;
    private final FileChannel fileChannel;
    private final MappedByteBuffer mappedByteBuffer;
    private final IndexHeader indexHeader;

    public IndexFile(final String fileName, final int hashSlotNum, final int indexNum,
        final long endPhyOffset, final long endTimestamp) throws IOException {
        int fileTotalSize =
            IndexHeader.INDEX_HEADER_SIZE + (hashSlotNum * hashSlotSize) + (indexNum * indexSize);
        this.mappedFile = new MappedFile(fileName, fileTotalSize);
        this.fileChannel = this.mappedFile.getFileChannel();
        this.mappedByteBuffer = this.mappedFile.getMappedByteBuffer();
        this.hashSlotNum = hashSlotNum;
        this.indexNum = indexNum;

        ByteBuffer byteBuffer = this.mappedByteBuffer.slice();
        this.indexHeader = new IndexHeader(byteBuffer);

        if (endPhyOffset > 0) {
            this.indexHeader.setBeginPhyOffset(endPhyOffset);
            this.indexHeader.setEndPhyOffset(endPhyOffset);
        }

        if (endTimestamp > 0) {
            this.indexHeader.setBeginTimestamp(endTimestamp);
            this.indexHeader.setEndTimestamp(endTimestamp);
        }
    }

    public String getFileName() {
        return this.mappedFile.getFileName();
    }

    public void load() {
        this.indexHeader.load();
    }

    //持久化到磁盘物理文件
    public void flush() {
        long beginTime = System.currentTimeMillis();
        if (this.mappedFile.hold()) {
            this.indexHeader.updateByteBuffer();
            this.mappedByteBuffer.force();
            this.mappedFile.release();
            log.info("flush index file eclipse time(ms) " + (System.currentTimeMillis() - beginTime));
        }
    }

    public boolean isWriteFull() {
        return this.indexHeader.getIndexCount() >= this.indexNum;
    }

    public boolean destroy(final long intervalForcibly) {
        return this.mappedFile.destroy(intervalForcibly);
    }

    public boolean putKey(final String key, final long phyOffset, final long storeTimestamp) {
        //判断是否超索引文件容量    //1、判断index是否已满，已满返回失败，由调用方来处理
        if (this.indexHeader.getIndexCount() < this.indexNum) {
            int keyHash = indexKeyHashMethod(key);  //2、计算key的非负hashCode，调用的java String的hashcode方法
            //3、key应该放的slot
            int slotPos = keyHash % this.hashSlotNum;
            //4、slot的数据存储位置
            int absSlotPos = IndexHeader.INDEX_HEADER_SIZE + slotPos * hashSlotSize;

            FileLock fileLock = null;

            try {

                // fileLock = this.fileChannel.lock(absSlotPos, hashSlotSize,
                // false);
                //5、如果存在hash冲突，获取这个slot存的前一个index的计数，如果没有则值为0
                int slotValue = this.mappedByteBuffer.getInt(absSlotPos);
                if (slotValue <= invalidIndex || slotValue > this.indexHeader.getIndexCount()) {
                    slotValue = invalidIndex;
                }
                //6、计算当前msg的存储时间和第一条msg相差秒数
                long timeDiff = storeTimestamp - this.indexHeader.getBeginTimestamp();

                timeDiff = timeDiff / 1000;

                if (this.indexHeader.getBeginTimestamp() <= 0) {
                    timeDiff = 0;
                } else if (timeDiff > Integer.MAX_VALUE) {
                    timeDiff = Integer.MAX_VALUE;
                } else if (timeDiff < 0) {
                    timeDiff = 0;
                }
                //7、获取该条index实际存储position
                int absIndexPos =
                    IndexHeader.INDEX_HEADER_SIZE + this.hashSlotNum * hashSlotSize
                        + this.indexHeader.getIndexCount() * indexSize;
                //8、生成一个index的unit内容
               /* 1. key hash value: message key的hash值
                2. phyOffset: message在CommitLog的物理文件地址, 可以直接查询到该消息(索引的核心机制)
                3. timeDiff: message的落盘时间与header里的beginTimestamp的差值
                (为了节省存储空间，如果直接存message的落盘时间就得8bytes)
                4. prevIndex: hash冲突处理的关键之处, 相同hash值上一个消息索引的index
                (如果当前消息索引是该hash值的第一个索引，则prevIndex=0, 也是消息索引查找时的停止条件。)*/
                this.mappedByteBuffer.putInt(absIndexPos, keyHash);
                this.mappedByteBuffer.putLong(absIndexPos + 4, phyOffset);
                this.mappedByteBuffer.putInt(absIndexPos + 4 + 8, (int) timeDiff);
                this.mappedByteBuffer.putInt(absIndexPos + 4 + 8 + 4, slotValue);
                //9、更新slot中的值为本条消息的顺序号
                this.mappedByteBuffer.putInt(absSlotPos, this.indexHeader.getIndexCount());
                //10、如果是第一条消息，更新header中的起始offset和起始time
                if (this.indexHeader.getIndexCount() <= 1) {
                    this.indexHeader.setBeginPhyOffset(phyOffset);
                    this.indexHeader.setBeginTimestamp(storeTimestamp);
                }
                //11、更新header中的计数
                this.indexHeader.incHashSlotCount();
                this.indexHeader.incIndexCount();//增加index计数
                this.indexHeader.setEndPhyOffset(phyOffset);//最后一条消息的offset
                this.indexHeader.setEndTimestamp(storeTimestamp);//最后一个index的时间

                return true;
            } catch (Exception e) {
                log.error("putKey exception, Key: " + key + " KeyHashCode: " + key.hashCode(), e);
            } finally {
                if (fileLock != null) {
                    try {
                        fileLock.release();
                    } catch (IOException e) {
                        log.error("Failed to release the lock", e);
                    }
                }
            }
        } else {
            log.warn("Over index file capacity: index count = " + this.indexHeader.getIndexCount()
                + "; index max num = " + this.indexNum);
        }

        return false;
    }

    public int indexKeyHashMethod(final String key) {
        int keyHash = key.hashCode();
        int keyHashPositive = Math.abs(keyHash);
        if (keyHashPositive < 0)
            keyHashPositive = 0;
        return keyHashPositive;
    }

    public long getBeginTimestamp() {
        return this.indexHeader.getBeginTimestamp();
    }

    public long getEndTimestamp() {
        return this.indexHeader.getEndTimestamp();
    }

    public long getEndPhyOffset() {
        return this.indexHeader.getEndPhyOffset();
    }

    public boolean isTimeMatched(final long begin, final long end) {
        boolean result = begin < this.indexHeader.getBeginTimestamp() && end > this.indexHeader.getEndTimestamp();
        result = result || (begin >= this.indexHeader.getBeginTimestamp() && begin <= this.indexHeader.getEndTimestamp());
        result = result || (end >= this.indexHeader.getBeginTimestamp() && end <= this.indexHeader.getEndTimestamp());
        return result;
    }

    public void selectPhyOffset(final List<Long> phyOffsets, final String key, final int maxNum,
        final long begin, final long end, boolean lock) {
        if (this.mappedFile.hold()) {
            //1、跟生成索引时一样，找到key的slot
            int keyHash = indexKeyHashMethod(key);
            //2. 计算该hash value 对应的hash slot位置
            int slotPos = keyHash % this.hashSlotNum;
            //3. 计算该hash value 对应的hash slot物理文件位置
            int absSlotPos = IndexHeader.INDEX_HEADER_SIZE + slotPos * hashSlotSize;

            FileLock fileLock = null;
            try {
                if (lock) {
                    // fileLock = this.fileChannel.lock(absSlotPos,
                    // hashSlotSize, true);
                }
                //2、获取该槽位上的最后一条索引的序号   //4. 取出该hash slot 的值
                int slotValue = this.mappedByteBuffer.getInt(absSlotPos);
                // if (fileLock != null) {
                // fileLock.release();
                // fileLock = null;
                // }
                //5. 该slot value <= 0 就代表没有该key对应的消息索引,直接结束搜索
                //   该slot value > maxIndexCount 就代表该key对应的消息索引超过最大限制，数据有误,直接结束搜索
                if (slotValue <= invalidIndex || slotValue > this.indexHeader.getIndexCount()
                    || this.indexHeader.getIndexCount() <= 1) {
                } else { //6. 从当前slot value 开始搜索
                    for (int nextIndexToRead = slotValue; ; ) {
                        if (phyOffsets.size() >= maxNum) {//到达最大返回条数
                            break;
                        }
                        //3、找到index的位置   //7. 找到当前slot value(也就是index count)物理文件位置
                        int absIndexPos =
                            IndexHeader.INDEX_HEADER_SIZE + this.hashSlotNum * hashSlotSize
                                + nextIndexToRead * indexSize;
                        //8. 读取消息索引数据
                        int keyHashRead = this.mappedByteBuffer.getInt(absIndexPos);
                        //4、在commitlog中偏移
                        long phyOffsetRead = this.mappedByteBuffer.getLong(absIndexPos + 4);

                        long timeDiff = (long) this.mappedByteBuffer.getInt(absIndexPos + 4 + 8);
                        //5、相同hashcode的前一条消息的序号
                        //9. 获取该消息索引的上一个消息索引index(可以看成链表的prev 指向上一个链节点的引用)
                        int prevIndexRead = this.mappedByteBuffer.getInt(absIndexPos + 4 + 8 + 4);
                        //10. 数据校验
                        if (timeDiff < 0) {
                            break;
                        }

                        timeDiff *= 1000L;

                        long timeRead = this.indexHeader.getBeginTimestamp() + timeDiff;
                        boolean timeMatched = (timeRead >= begin) && (timeRead <= end);
                        //6、Hash和time都符合条件，加入返回列表
                        //10. 数据校验比对 hash值和落盘时间
                        if (keyHash == keyHashRead && timeMatched) {
                            phyOffsets.add(phyOffsetRead);
                        }
                        //当prevIndex <= 0 或prevIndex > maxIndexCount
                        // 或prevIndexRead == nextIndexToRead 或 timeRead < begin 停止搜索
                        if (prevIndexRead <= invalidIndex
                            || prevIndexRead > this.indexHeader.getIndexCount()
                            || prevIndexRead == nextIndexToRead || timeRead < begin) {
                            break;
                        }
                        //7、前一条不等于0，继续读入前一条
                        nextIndexToRead = prevIndexRead;
                    }
                }
            } catch (Exception e) {
                log.error("selectPhyOffset exception ", e);
            } finally {
                if (fileLock != null) {
                    try {
                        fileLock.release();
                    } catch (IOException e) {
                        log.error("Failed to release the lock", e);
                    }
                }

                this.mappedFile.release();
            }
        }
    }
}
