/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

package org.apache.kylin.engine.mr.steps;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.hadoop.io.Text;
import org.apache.kylin.common.KylinVersion;
import org.apache.kylin.common.util.Bytes;
import org.apache.kylin.common.util.StringUtil;
import org.apache.kylin.cube.cuboid.CuboidUtil;
import org.apache.kylin.engine.mr.common.BatchConstants;
import org.apache.kylin.engine.mr.common.StatisticsDecisionUtil;
import org.apache.kylin.measure.BufferedMeasureCodec;
import org.apache.kylin.measure.hllc.HLLCounter;
import org.apache.kylin.measure.hllc.RegisterType;
import org.apache.kylin.metadata.datatype.DataType;
import org.apache.kylin.metadata.model.TblColRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Sets;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

/**
 */
public class FactDistinctColumnsMapper<KEYIN> extends FactDistinctColumnsMapperBase<KEYIN, Object> {

    private static final Logger logger = LoggerFactory.getLogger(FactDistinctColumnsMapper.class);

    public static enum RawDataCounter {
        BYTES
    }

    protected boolean collectStatistics = false;
    protected int nRowKey;
    private Integer[][] allCuboidsBitSet = null;
    private HLLCounter[] allCuboidsHLL = null;
    private Long[] cuboidIds;
    private int rowCount = 0;
    private int samplingPercentage;
    private ByteBuffer tmpbuf;

    private CuboidStatCalculator[] cuboidStatCalculators;

    private static final Text EMPTY_TEXT = new Text();
    public static final byte MARK_FOR_PARTITION_COL = (byte) 0xFE;
    public static final byte MARK_FOR_HLL = (byte) 0xFF;

    private int partitionColumnIndex = -1;
    private boolean needFetchPartitionCol = true;

    private SelfDefineSortableKey sortableKey = new SelfDefineSortableKey();

    @Override
    protected void doSetup(Context context) throws IOException {
        super.doSetup(context);
        tmpbuf = ByteBuffer.allocate(4096);
        collectStatistics = Boolean.parseBoolean(context.getConfiguration().get(BatchConstants.CFG_STATISTICS_ENABLED));
        if (collectStatistics) {
            samplingPercentage = Integer
                    .parseInt(context.getConfiguration().get(BatchConstants.CFG_STATISTICS_SAMPLING_PERCENT));
            nRowKey = cubeDesc.getRowkey().getRowKeyColumns().length;

            Set<Long> cuboidIdSet = Sets.newHashSet(cubeSeg.getCuboidScheduler().getAllCuboidIds());
            if (StatisticsDecisionUtil.isAbleToOptimizeCubingPlan(cubeSeg)) {
                // For cube planner, for every prebuilt cuboid, its related row count stats should be calculated
                // If the precondition for trigger cube planner phase one is satisfied, we need to calculate row count stats for mandatory cuboids.
                cuboidIdSet.addAll(cubeSeg.getCubeDesc().getMandatoryCuboids());
            }
            cuboidIds = cuboidIdSet.toArray(new Long[cuboidIdSet.size()]);
            allCuboidsBitSet = CuboidUtil.getCuboidBitSet(cuboidIds, nRowKey);

            allCuboidsHLL = new HLLCounter[cuboidIds.length];
            for (int i = 0; i < cuboidIds.length; i++) {
                allCuboidsHLL[i] = new HLLCounter(cubeDesc.getConfig().getCubeStatsHLLPrecision(), RegisterType.DENSE);
            }

            TblColRef partitionColRef = cubeDesc.getModel().getPartitionDesc().getPartitionDateColumnRef();
            if (partitionColRef != null) {
                partitionColumnIndex = intermediateTableDesc.getColumnIndex(partitionColRef);
            }

            // check whether need fetch the partition col values
            if (partitionColumnIndex < 0) {
                // if partition col not on cube, no need
                needFetchPartitionCol = false;
            } else {
                needFetchPartitionCol = true;
            }
            //for KYLIN-2518 backward compatibility
            boolean isUsePutRowKeyToHllNewAlgorithm;
            if (KylinVersion.isBefore200(cubeDesc.getVersion())) {
                isUsePutRowKeyToHllNewAlgorithm = false;
                logger.info("Found KylinVersion : {}. Use old algorithm for cuboid sampling.", cubeDesc.getVersion());
            } else {
                isUsePutRowKeyToHllNewAlgorithm = true;
                logger.info(
                        "Found KylinVersion : {}. Use new algorithm for cuboid sampling. About the details of the new algorithm, please refer to KYLIN-2518",
                        cubeDesc.getVersion());
            }

            int calculatorNum = getStatsThreadNum(cuboidIds.length);
            cuboidStatCalculators = new CuboidStatCalculator[calculatorNum];
            int splitSize = cuboidIds.length / calculatorNum;
            if (splitSize <= 0) {
                splitSize = 1;
            }
            for (int i = 0; i < calculatorNum; i++) {
                HLLCounter[] cuboidsHLLSplit;
                Integer[][] cuboidsBitSetSplit;
                Long[] cuboidIdSplit;
                int start = i * splitSize;
                if (start >= cuboidIds.length) {
                    break;
                }
                int end = (i + 1) * splitSize;
                if (i == calculatorNum - 1) {// last split
                    end = cuboidIds.length;
                }

                cuboidsHLLSplit = Arrays.copyOfRange(allCuboidsHLL, start, end);
                cuboidsBitSetSplit = Arrays.copyOfRange(allCuboidsBitSet, start, end);
                cuboidIdSplit = Arrays.copyOfRange(cuboidIds, start, end);
                CuboidStatCalculator calculator = new CuboidStatCalculator(i,
                        intermediateTableDesc.getRowKeyColumnIndexes(), cuboidIdSplit, cuboidsBitSetSplit,
                        isUsePutRowKeyToHllNewAlgorithm, cuboidsHLLSplit);
                cuboidStatCalculators[i] = calculator;
                calculator.start();
            }
        }
    }

    private int getStatsThreadNum(int cuboidNum) {
        int unitNum = cubeDesc.getConfig().getCuboidNumberPerStatsCalculator();
        if (unitNum <= 0) {
            logger.warn("config from getCuboidNumberPerStatsCalculator() " + unitNum + " is should larger than 0");
            logger.info("Will use single thread for cuboid statistics calculation");
            return 1;
        }

        int maxCalculatorNum = cubeDesc.getConfig().getCuboidStatsCalculatorMaxNumber();
        int calculatorNum = (cuboidNum - 1) / unitNum + 1;
        if (calculatorNum > maxCalculatorNum) {
            calculatorNum = maxCalculatorNum;
        }
        return calculatorNum;
    }

    @Override
    public void doMap(KEYIN key, Object record, Context context) throws IOException, InterruptedException {
        Collection<String[]> rowCollection = flatTableInputFormat.parseMapperInput(record);

        for (String[] row : rowCollection) {
            context.getCounter(RawDataCounter.BYTES).increment(countSizeInBytes(row));
            for (int i = 0; i < dictCols.size(); i++) {
                String fieldValue = row[dictionaryColumnIndex[i]];
                if (fieldValue == null)
                    continue;

                int reducerIndex;
                if (uhcIndex[i] == 0) {
                    //for the normal dictionary column
                    reducerIndex = columnIndexToReducerBeginId.get(i);
                } else {
                    //for the uhc
                    reducerIndex = columnIndexToReducerBeginId.get(i)
                            + (fieldValue.hashCode() & 0x7fffffff) % uhcReducerCount;
                }

                tmpbuf.clear();
                byte[] valueBytes = Bytes.toBytes(fieldValue);
                int size = valueBytes.length + 1;
                if (size >= tmpbuf.capacity()) {
                    tmpbuf = ByteBuffer.allocate(countNewSize(tmpbuf.capacity(), size));
                }
                tmpbuf.put(Bytes.toBytes(reducerIndex)[3]);
                tmpbuf.put(valueBytes);
                outputKey.set(tmpbuf.array(), 0, tmpbuf.position());
                DataType type = dictCols.get(i).getType();
                sortableKey.init(outputKey, type);
                //judge type
                context.write(sortableKey, EMPTY_TEXT);

                // log a few rows for troubleshooting
                if (rowCount < 10) {
                    logger.info(
                            "Sample output: " + dictCols.get(i) + " '" + fieldValue + "' => reducer " + reducerIndex);
                }
            }

            if (collectStatistics) {
                if (rowCount % 100 < samplingPercentage) {
                    putRowKeyToHLL(row);
                }

                if (needFetchPartitionCol == true) {
                    String fieldValue = row[partitionColumnIndex];
                    if (fieldValue != null) {
                        tmpbuf.clear();
                        byte[] valueBytes = Bytes.toBytes(fieldValue);
                        int size = valueBytes.length + 1;
                        if (size >= tmpbuf.capacity()) {
                            tmpbuf = ByteBuffer.allocate(countNewSize(tmpbuf.capacity(), size));
                        }
                        tmpbuf.put(MARK_FOR_PARTITION_COL);
                        tmpbuf.put(valueBytes);
                        outputKey.set(tmpbuf.array(), 0, tmpbuf.position());
                        sortableKey.init(outputKey, (byte) 0);
                        context.write(sortableKey, EMPTY_TEXT);
                    }
                }
            }
            rowCount++;
        }
    }

    private void putRowKeyToHLL(String[] row) {
        for (CuboidStatCalculator cuboidStatCalculator : cuboidStatCalculators) {
            cuboidStatCalculator.putRow(row);
        }
    }

    private long countSizeInBytes(String[] row) {
        int size = 0;
        for (String s : row) {
            size += s == null ? 1 : StringUtil.utf8Length(s);
            size++; // delimiter
        }
        return size;
    }

    @Override
    protected void doCleanup(Context context) throws IOException, InterruptedException {
        if (collectStatistics) {
            ByteBuffer hllBuf = ByteBuffer.allocate(BufferedMeasureCodec.DEFAULT_BUFFER_SIZE);
            // output each cuboid's hll to reducer, key is 0 - cuboidId
            for (CuboidStatCalculator cuboidStatCalculator : cuboidStatCalculators) {
                cuboidStatCalculator.waitForCompletion();
            }
            for (CuboidStatCalculator cuboidStatCalculator : cuboidStatCalculators) {
                Long[] cuboidIds = cuboidStatCalculator.getCuboidIds();
                HLLCounter[] cuboidsHLL = cuboidStatCalculator.getHLLCounters();
                HLLCounter hll;

                for (int i = 0; i < cuboidIds.length; i++) {
                    hll = cuboidsHLL[i];
                    tmpbuf.clear();
                    tmpbuf.put(MARK_FOR_HLL); // one byte
                    tmpbuf.putLong(cuboidIds[i]);
                    outputKey.set(tmpbuf.array(), 0, tmpbuf.position());
                    hllBuf.clear();
                    hll.writeRegisters(hllBuf);
                    outputValue.set(hllBuf.array(), 0, hllBuf.position());
                    sortableKey.init(outputKey, (byte) 0);
                    context.write(sortableKey, outputValue);
                }
            }
        }
    }

    private int countNewSize(int oldSize, int dataSize) {
        int newSize = oldSize * 2;
        while (newSize < dataSize) {
            newSize = newSize * 2;
        }
        return newSize;
    }

    public static class CuboidStatCalculator implements Runnable {
        private final int id;
        private final int nRowKey;
        private final int[] rowkeyColIndex;
        private final Long[] cuboidIds;
        private final Integer[][] cuboidsBitSet;
        private volatile HLLCounter[] cuboidsHLL = null;

        //about details of the new algorithm, please see KYLIN-2518
        private final boolean isNewAlgorithm;
        private final HashFunction hf;
        private long[] rowHashCodesLong;

        private BlockingQueue<String[]> queue = new LinkedBlockingQueue<String[]>(2000);
        private Thread workThread;
        private volatile boolean stop;

        public CuboidStatCalculator(int id, int[] rowkeyColIndex, Long[] cuboidIds, Integer[][] cuboidsBitSet,
                boolean isUsePutRowKeyToHllNewAlgorithm, HLLCounter[] cuboidsHLL) {
            this.id = id;
            this.nRowKey = rowkeyColIndex.length;
            this.rowkeyColIndex = rowkeyColIndex;
            this.cuboidIds = cuboidIds;
            this.cuboidsBitSet = cuboidsBitSet;
            this.isNewAlgorithm = isUsePutRowKeyToHllNewAlgorithm;
            if (!isNewAlgorithm) {
                this.hf = Hashing.murmur3_32();
            } else {
                rowHashCodesLong = new long[nRowKey];
                this.hf = Hashing.murmur3_128();
            }
            this.cuboidsHLL = cuboidsHLL;
            workThread = new Thread(this);
        }

        public void start() {
            logger.info("cuboid stats calculator:" + id + " started, handle cuboids number:" + cuboidIds.length);
            workThread.start();
        }

        public void putRow(final String[] row) {
            String[] copyRow = Arrays.copyOf(row, row.length);
            try {
                queue.put(copyRow);
            } catch (InterruptedException e) {
                logger.error("interrupt", e);
            }
        }

        public void waitForCompletion() {
            stop = true;
            try {
                workThread.join();
            } catch (InterruptedException e) {
                logger.error("interrupt", e);
            }
        }

        private void putRowKeyToHLLOld(String[] row) {
            //generate hash for each row key column
            byte[][] rowHashCodes = new byte[nRowKey][];
            for (int i = 0; i < nRowKey; i++) {
                Hasher hc = hf.newHasher();
                String colValue = row[rowkeyColIndex[i]];
                if (colValue != null) {
                    rowHashCodes[i] = hc.putString(colValue).hash().asBytes();
                } else {
                    rowHashCodes[i] = hc.putInt(0).hash().asBytes();
                }
            }

            // user the row key column hash to get a consolidated hash for each cuboid
            for (int i = 0, n = cuboidsBitSet.length; i < n; i++) {
                Hasher hc = hf.newHasher();
                for (int position = 0; position < cuboidsBitSet[i].length; position++) {
                    hc.putBytes(rowHashCodes[cuboidsBitSet[i][position]]);
                }

                cuboidsHLL[i].add(hc.hash().asBytes());
            }
        }

        private void putRowKeyToHLLNew(String[] row) {
            //generate hash for each row key column
            for (int i = 0; i < nRowKey; i++) {
                Hasher hc = hf.newHasher();
                String colValue = row[rowkeyColIndex[i]];
                if (colValue == null)
                    colValue = "0";
                byte[] bytes = hc.putString(colValue).hash().asBytes();
                rowHashCodesLong[i] = (Bytes.toLong(bytes) + i);//add column ordinal to the hash value to distinguish between (a,b) and (b,a)
            }

            // user the row key column hash to get a consolidated hash for each cuboid
            for (int i = 0, n = cuboidsBitSet.length; i < n; i++) {
                long value = 0;
                for (int position = 0; position < cuboidsBitSet[i].length; position++) {
                    value += rowHashCodesLong[cuboidsBitSet[i][position]];
                }
                cuboidsHLL[i].addHashDirectly(value);
            }
        }

        public HLLCounter[] getHLLCounters() {
            return cuboidsHLL;
        }

        public Long[] getCuboidIds() {
            return cuboidIds;
        }

        @Override
        public void run() {
            while (true) {
                String[] row = queue.poll();
                if (row == null && stop) {
                    logger.info("cuboid stats calculator:" + id + " completed.");
                    break;
                } else if (row == null) {
                    Thread.yield();
                    continue;
                }
                if (isNewAlgorithm) {
                    putRowKeyToHLLNew(row);
                } else {
                    putRowKeyToHLLOld(row);
                }
            }
        }
    }
}
