/*
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
package io.trino.operator;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import io.trino.spi.Page;
import io.trino.spi.PageBuilder;
import io.trino.spi.TrinoException;
import io.trino.spi.block.Block;
import io.trino.spi.block.BlockBuilder;
import io.trino.spi.block.ColumnarRow;
import io.trino.spi.type.Type;

import java.util.List;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Verify.verify;
import static io.trino.spi.StandardErrorCode.CONSTRAINT_VIOLATION;
import static io.trino.spi.block.ColumnarRow.toColumnarRow;
import static io.trino.spi.connector.ConnectorMergeSink.DELETE_OPERATION_NUMBER;
import static io.trino.spi.connector.ConnectorMergeSink.INSERT_OPERATION_NUMBER;
import static io.trino.spi.connector.ConnectorMergeSink.UPDATE_OPERATION_NUMBER;
import static io.trino.spi.type.TinyintType.TINYINT;
import static java.lang.Math.toIntExact;
import static java.util.Objects.requireNonNull;

public class DeleteAndInsertMergeProcessor
        implements MergeRowChangeProcessor
{
    private final List<Type> dataColumnTypes;
    private final List<String> dataColumnNames;
    private final Type rowIdType;
    private final int rowIdChannel;
    private final int mergeRowChannel;
    private final List<Integer> dataColumnChannels;
    private final int redistributionColumnCount;
    private final List<Integer> redistributionChannelNumbers;
    private final Set<Integer> nonNullColumnChannels;

    public DeleteAndInsertMergeProcessor(
            List<Type> dataColumnTypes,
            List<String> dataColumnNames,
            Type rowIdType,
            int rowIdChannel,
            int mergeRowChannel,
            List<Integer> redistributionChannelNumbers,
            List<Integer> dataColumnChannels,
            Set<Integer> nonNullColumnChannels)
    {
        this.dataColumnTypes = requireNonNull(dataColumnTypes, "dataColumnTypes is null");
        this.dataColumnNames = requireNonNull(dataColumnNames, "dataColumnNames is null");
        this.rowIdType = requireNonNull(rowIdType, "rowIdType is null");
        this.rowIdChannel = rowIdChannel;
        this.mergeRowChannel = mergeRowChannel;
        this.redistributionColumnCount = redistributionChannelNumbers.size();
        int redistributionSourceIndex = 0;
        this.dataColumnChannels = requireNonNull(dataColumnChannels, "dataColumnChannels is null");
        ImmutableList.Builder<Integer> redistributionChannelNumbersBuilder = ImmutableList.builder();
        for (int dataColumnChannel : dataColumnChannels) {
            if (redistributionChannelNumbers.contains(dataColumnChannel)) {
                redistributionChannelNumbersBuilder.add(redistributionSourceIndex);
                redistributionSourceIndex++;
            }
            else {
                redistributionChannelNumbersBuilder.add(-1);
            }
        }
        this.redistributionChannelNumbers = redistributionChannelNumbersBuilder.build();
        this.nonNullColumnChannels = ImmutableSet.copyOf(requireNonNull(nonNullColumnChannels, "nonNullColumnChannels is null"));
    }

    @JsonProperty
    public List<Type> getDataColumnTypes()
    {
        return dataColumnTypes;
    }

    @JsonProperty
    public Type getRowIdType()
    {
        return rowIdType;
    }

    /**
     * Transform UPDATE operations into an INSERT and DELETE operation.
     * See {@link MergeRowChangeProcessor#transformPage} for details.
     */
    @Override
    public Page transformPage(Page inputPage)
    {
        requireNonNull(inputPage, "inputPage is null");
        int inputChannelCount = inputPage.getChannelCount();
        checkArgument(inputChannelCount >= 2 + redistributionColumnCount, "inputPage channelCount (%s) should be >= 2 + partition columns size (%s)", inputChannelCount, redistributionColumnCount);

        int originalPositionCount = inputPage.getPositionCount();
        checkArgument(originalPositionCount > 0, "originalPositionCount should be > 0, but is %s", originalPositionCount);

        ColumnarRow mergeRow = toColumnarRow(inputPage.getBlock(mergeRowChannel));
        Block operationChannelBlock = mergeRow.getField(mergeRow.getFieldCount() - 2);

        int updatePositions = 0;
        int insertPositions = 0;
        int deletePositions = 0;
        for (int position = 0; position < originalPositionCount; position++) {
            int operation = toIntExact(TINYINT.getLong(operationChannelBlock, position));
            switch (operation) {
                case DEFAULT_CASE_OPERATION_NUMBER -> { /* ignored */ }
                case INSERT_OPERATION_NUMBER -> insertPositions++;
                case DELETE_OPERATION_NUMBER -> deletePositions++;
                case UPDATE_OPERATION_NUMBER -> updatePositions++;
                default -> throw new IllegalArgumentException("Unknown operator number: " + operation);
            }
        }

        int totalPositions = insertPositions + deletePositions + (2 * updatePositions);
        List<Type> pageTypes = ImmutableList.<Type>builder()
                .addAll(dataColumnTypes)
                .add(TINYINT)
                .add(rowIdType)
                .add(TINYINT)
                .build();

        PageBuilder pageBuilder = new PageBuilder(totalPositions, pageTypes);
        for (int position = 0; position < originalPositionCount; position++) {
            long operation = TINYINT.getLong(operationChannelBlock, position);
            if (operation != DEFAULT_CASE_OPERATION_NUMBER) {
                // Delete and Update because both create a delete row
                if (operation == DELETE_OPERATION_NUMBER || operation == UPDATE_OPERATION_NUMBER) {
                    addDeleteRow(pageBuilder, inputPage, position);
                }
                // Insert and update because both create an insert row
                if (operation == INSERT_OPERATION_NUMBER || operation == UPDATE_OPERATION_NUMBER) {
                    addInsertRow(pageBuilder, mergeRow, position, operation == UPDATE_OPERATION_NUMBER);
                }
            }
        }

        Page page = pageBuilder.build();
        int positionCount = page.getPositionCount();
        for (int nonNullColumnChannel : nonNullColumnChannels) {
            Block nonNullBlock = page.getBlock(nonNullColumnChannel);
            Block operationBlock = page.getBlock(dataColumnChannels.size());
            if (nonNullBlock.mayHaveNull()) {
                for (int position = 0; position < positionCount; position++) {
                    if (TINYINT.getLong(operationBlock, position) == INSERT_OPERATION_NUMBER && nonNullBlock.isNull(position)) {
                        throw new TrinoException(CONSTRAINT_VIOLATION, "Assigning NULL to non-null MERGE target table column " + dataColumnNames.get(nonNullColumnChannel));
                    }
                }
            }
        }
        verify(page.getPositionCount() == totalPositions, "page positions (%s) is not equal to (%s)", page.getPositionCount(), totalPositions);
        return page;
    }

    private void addDeleteRow(PageBuilder pageBuilder, Page originalPage, int position)
    {
        // TODO: There is no need to copy the data columns themselves.  Instead, we could
        //  use a DictionaryBlock to omit columns.
        // Copy the write redistribution columns
        for (int targetChannel : dataColumnChannels) {
            Type columnType = dataColumnTypes.get(targetChannel);
            BlockBuilder targetBlock = pageBuilder.getBlockBuilder(targetChannel);

            int redistributionChannelNumber = redistributionChannelNumbers.get(targetChannel);
            if (redistributionChannelNumbers.get(targetChannel) >= 0) {
                // The value comes from that column of the page
                columnType.appendTo(originalPage.getBlock(redistributionChannelNumber), position, targetBlock);
            }
            else {
                // We don't care about the other data columns
                targetBlock.appendNull();
            }
        }

        // Add the operation column == deleted
        TINYINT.writeLong(pageBuilder.getBlockBuilder(dataColumnChannels.size()), DELETE_OPERATION_NUMBER);

        // Copy row ID column
        rowIdType.appendTo(originalPage.getBlock(rowIdChannel), position, pageBuilder.getBlockBuilder(dataColumnChannels.size() + 1));

        // Write 0, meaning this row is not an insert derived from an update
        TINYINT.writeLong(pageBuilder.getBlockBuilder(dataColumnChannels.size() + 2), 0);

        pageBuilder.declarePosition();
    }

    private void addInsertRow(PageBuilder pageBuilder, ColumnarRow mergeCaseBlock, int position, boolean causedByUpdate)
    {
        // Copy the values from the merge block
        for (int targetChannel : dataColumnChannels) {
            Type columnType = dataColumnTypes.get(targetChannel);
            BlockBuilder targetBlock = pageBuilder.getBlockBuilder(targetChannel);
            // The value comes from that column of the page
            columnType.appendTo(mergeCaseBlock.getField(targetChannel), position, targetBlock);
        }

        // Add the operation column == insert
        TINYINT.writeLong(pageBuilder.getBlockBuilder(dataColumnChannels.size()), INSERT_OPERATION_NUMBER);

        // Add null row ID column
        pageBuilder.getBlockBuilder(dataColumnChannels.size() + 1).appendNull();

        // Write 1 if this row is an insert derived from an update, 0 otherwise
        TINYINT.writeLong(pageBuilder.getBlockBuilder(dataColumnChannels.size() + 2), causedByUpdate ? 1 : 0);

        pageBuilder.declarePosition();
    }
}
