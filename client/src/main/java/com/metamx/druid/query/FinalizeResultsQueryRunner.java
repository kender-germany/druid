/*
 * Druid - a distributed column store.
 * Copyright (C) 2012  Metamarkets Group Inc.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package com.metamx.druid.query;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.metamx.common.guava.Sequence;
import com.metamx.common.guava.Sequences;
import com.metamx.druid.Query;
import com.metamx.druid.aggregation.AggregatorFactory;
import com.metamx.druid.result.BySegmentResultValueClass;
import com.metamx.druid.result.Result;

import javax.annotation.Nullable;

/**
 */
public class FinalizeResultsQueryRunner<T> implements QueryRunner<T>
{
  private final QueryRunner<T> baseRunner;
  private final QueryToolChest<T, Query<T>> toolChest;

  public FinalizeResultsQueryRunner(
      QueryRunner<T> baseRunner,
      QueryToolChest<T, Query<T>> toolChest
  )
  {
    this.baseRunner = baseRunner;
    this.toolChest = toolChest;
  }

  @Override
  public Sequence<T> run(final Query<T> query)
  {
    final boolean isBySegment = Boolean.parseBoolean(query.getContextValue("bySegment"));
    final boolean shouldFinalize = Boolean.parseBoolean(query.getContextValue("finalize", "true"));
    if (shouldFinalize) {
      Function<T, T> finalizerFn;
      if (isBySegment) {
        finalizerFn = new Function<T, T>()
        {
          final Function<T, T> baseFinalizer = toolChest.makeMetricManipulatorFn(
              query,
              new MetricManipulationFn()
              {
                @Override
                public Object manipulate(AggregatorFactory factory, Object object)
                {
                  return factory.finalizeComputation(factory.deserialize(object));
                }
              }
          );

          @Override
          @SuppressWarnings("unchecked")
          public T apply(@Nullable T input)
          {
            Result<BySegmentResultValueClass<T>> result = (Result<BySegmentResultValueClass<T>>) input;
            BySegmentResultValueClass<T> resultsClass = result.getValue();

            return (T) new Result<BySegmentResultValueClass>(
                result.getTimestamp(),
                new BySegmentResultValueClass(
                    Lists.transform(resultsClass.getResults(), baseFinalizer),
                    resultsClass.getSegmentId(),
                    resultsClass.getIntervalString()
                )
            );
          }
        };
      }
      else {
        finalizerFn = toolChest.makeMetricManipulatorFn(
            query,
            new MetricManipulationFn()
            {
              @Override
              public Object manipulate(AggregatorFactory factory, Object object)
              {
                return factory.finalizeComputation(object);
              }
            }
        );
      }

      return Sequences.map(
          baseRunner.run(query.withOverriddenContext(ImmutableMap.of("finalize", "false"))),
          finalizerFn
      );
    }
    return baseRunner.run(query);
  }
}
