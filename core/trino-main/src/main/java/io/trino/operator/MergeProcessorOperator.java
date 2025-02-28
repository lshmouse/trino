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

import io.trino.spi.Page;
import io.trino.sql.planner.plan.PlanNodeId;
import io.trino.sql.planner.plan.TableWriterNode.MergeParadigmAndTypes;

import java.util.List;
import java.util.Set;

import static com.google.common.base.Preconditions.checkState;
import static io.trino.operator.BasicWorkProcessorOperatorAdapter.createAdapterOperatorFactory;
import static io.trino.operator.WorkProcessor.TransformationState.finished;
import static io.trino.operator.WorkProcessor.TransformationState.ofResult;
import static java.util.Objects.requireNonNull;

/**
 * This operator is used by operations like SQL MERGE.  It is used
 * for all {@link io.trino.spi.connector.RowChangeParadigm}s.  This operator
 * creates the {@link MergeRowChangeProcessor}.
 */
public class MergeProcessorOperator
        implements WorkProcessorOperator
{
    public static OperatorFactory createOperatorFactory(
            int operatorId,
            PlanNodeId planNodeId,
            MergeParadigmAndTypes merge,
            int rowIdChannel,
            int mergeRowChannel,
            List<Integer> redistributionColumnChannels,
            List<Integer> dataColumnChannels,
            Set<Integer> nonNullColumnChannels)
    {
        MergeRowChangeProcessor rowChangeProcessor = switch (merge.getParadigm()) {
            case DELETE_ROW_AND_INSERT_ROW -> new DeleteAndInsertMergeProcessor(
                    merge.getColumnTypes(),
                    merge.getColumnNames(),
                    merge.getRowIdType(),
                    rowIdChannel,
                    mergeRowChannel,
                    redistributionColumnChannels,
                    dataColumnChannels,
                    nonNullColumnChannels);
            case CHANGE_ONLY_UPDATED_COLUMNS -> new ChangeOnlyUpdatedColumnsMergeProcessor(
                    rowIdChannel,
                    mergeRowChannel,
                    dataColumnChannels,
                    merge.getColumnNames(),
                    redistributionColumnChannels,
                    nonNullColumnChannels);
        };
        return createAdapterOperatorFactory(new Factory(operatorId, planNodeId, rowChangeProcessor));
    }

    public static class Factory
            implements BasicWorkProcessorOperatorAdapter.BasicAdapterWorkProcessorOperatorFactory
    {
        private final int operatorId;
        private final PlanNodeId planNodeId;
        private final MergeRowChangeProcessor rowChangeProcessor;
        private boolean closed;

        public Factory(int operatorId, PlanNodeId planNodeId, MergeRowChangeProcessor rowChangeProcessor)
        {
            this.operatorId = operatorId;
            this.planNodeId = requireNonNull(planNodeId, "planNodeId is null");
            this.rowChangeProcessor = requireNonNull(rowChangeProcessor, "rowChangeProcessor is null");
        }

        @Override
        public WorkProcessorOperator create(ProcessorContext processorContext, WorkProcessor<Page> sourcePages)
        {
            checkState(!closed, "Factory is already closed");
            return new MergeProcessorOperator(sourcePages, rowChangeProcessor);
        }

        @Override
        public int getOperatorId()
        {
            return operatorId;
        }

        @Override
        public PlanNodeId getPlanNodeId()
        {
            return planNodeId;
        }

        @Override
        public String getOperatorType()
        {
            return MergeProcessorOperator.class.getSimpleName();
        }

        @Override
        public void close()
        {
            closed = true;
        }

        @Override
        public Factory duplicate()
        {
            return new Factory(operatorId, planNodeId, rowChangeProcessor);
        }
    }

    private final WorkProcessor<Page> pages;

    private MergeProcessorOperator(
            WorkProcessor<Page> sourcePages,
            MergeRowChangeProcessor rowChangeProcessor)
    {
        pages = sourcePages
                .transform(page -> {
                    if (page == null) {
                        return finished();
                    }
                    return ofResult(rowChangeProcessor.transformPage(page));
                });
    }

    @Override
    public WorkProcessor<Page> getOutputPages()
    {
        return pages;
    }
}
