/*
 * Copyright (c) 2014, Cloudera, Inc. All Rights Reserved.
 *
 * Cloudera, Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"). You may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for
 * the specific language governing permissions and limitations under the
 * License.
 */

package com.cloudera.dataflow.spark;

import com.google.api.client.util.Maps;
import com.google.cloud.dataflow.sdk.coders.Coder;
import com.google.cloud.dataflow.sdk.io.AvroIO;
import com.google.cloud.dataflow.sdk.io.TextIO;
import com.google.cloud.dataflow.sdk.transforms.Combine;
import com.google.cloud.dataflow.sdk.transforms.Create;
import com.google.cloud.dataflow.sdk.transforms.Flatten;
import com.google.cloud.dataflow.sdk.transforms.GroupByKey;
import com.google.cloud.dataflow.sdk.transforms.PTransform;
import com.google.cloud.dataflow.sdk.transforms.ParDo;
import com.google.cloud.dataflow.sdk.transforms.View;
import com.google.cloud.dataflow.sdk.util.WindowedValue;
import com.google.cloud.dataflow.sdk.values.KV;
import com.google.cloud.dataflow.sdk.values.PCollection;
import com.google.cloud.dataflow.sdk.values.PCollectionList;
import com.google.cloud.dataflow.sdk.values.PCollectionTuple;
import com.google.cloud.dataflow.sdk.values.PCollectionView;
import com.google.cloud.dataflow.sdk.values.TupleTag;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import java.io.IOException;
import org.apache.avro.mapred.AvroKey;
import org.apache.avro.mapreduce.AvroJob;
import org.apache.avro.mapreduce.AvroKeyInputFormat;
import org.apache.avro.mapreduce.AvroKeyOutputFormat;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.mapreduce.Job;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaRDDLike;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.api.java.function.Function2;
import org.apache.spark.api.java.function.PairFlatMapFunction;
import org.apache.spark.api.java.function.PairFunction;
import scala.Tuple2;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Supports translation between a DataFlow transform, and Spark's operations on RDDs.
 */
public final class TransformTranslator {
  private static final Logger LOG = Logger.getLogger(TransformTranslator.class.getName());

  private TransformTranslator() {
  }

  private static class FieldGetter {
    private final Map<String, Field> fields;

    FieldGetter(Class<?> clazz) {
      this.fields = Maps.newHashMap();
      for (Field f : clazz.getDeclaredFields()) {
        f.setAccessible(true);
        this.fields.put(f.getName(), f);
      }
    }

    public <T> T get(String fieldname, Object value) {
      try {
        @SuppressWarnings("unchecked")
        T fieldValue = (T) fields.get(fieldname).get(value);
        return fieldValue;
      } catch (IllegalAccessException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  private static <T> TransformEvaluator<Flatten.FlattenPCollectionList<T>> flattenPColl() {
    return new TransformEvaluator<Flatten.FlattenPCollectionList<T>>() {
      @SuppressWarnings("unchecked")
      @Override
      public void evaluate(Flatten.FlattenPCollectionList<T> transform, EvaluationContext context) {
        PCollectionList<T> pcs = (PCollectionList<T>) context.getPipeline().getInput(transform);
        JavaRDD<T>[] rdds = new JavaRDD[pcs.size()];
        for (int i = 0; i < rdds.length; i++) {
          rdds[i] = (JavaRDD<T>) context.getRDD(pcs.get(i));
        }
        JavaRDD<T> rdd = context.getSparkContext().union(rdds);
        context.setOutputRDD(transform, rdd);
      }
    };
  }

  private static <K, V> TransformEvaluator<GroupByKey.GroupByKeyOnly<K, V>> gbk() {
    return new TransformEvaluator<GroupByKey.GroupByKeyOnly<K, V>>() {
      @Override
      public void evaluate(GroupByKey.GroupByKeyOnly<K, V> transform, EvaluationContext context) {
        @SuppressWarnings("unchecked")
        JavaRDDLike<KV<K, V>, ?> inRDD =
            (JavaRDDLike<KV<K, V>, ?>) context.getInputRDD(transform);
        context.setOutputRDD(transform, fromPair(toPair(inRDD).groupByKey()));
      }
    };
  }

  private static final FieldGetter GROUPED_FG = new FieldGetter(Combine.GroupedValues.class);

  private static <K, VI, VO> TransformEvaluator<Combine.GroupedValues<K, VI, VO>> grouped() {
    return new TransformEvaluator<Combine.GroupedValues<K, VI, VO>>() {
      @Override
      public void evaluate(Combine.GroupedValues<K, VI, VO> transform, EvaluationContext context) {
        Combine.KeyedCombineFn<K, VI, ?, VI> keyed = GROUPED_FG.get("fn", transform);
        @SuppressWarnings("unchecked")
        JavaRDDLike<KV<K, Iterable<VI>>, ?> inRDD =
            (JavaRDDLike<KV<K, Iterable<VI>>, ?>) context.getInputRDD(transform);
        context.setOutputRDD(transform, inRDD.map(new KVFunction<>(keyed)));
      }
    };
  }

  private static final FieldGetter COMBINE_PERKEY_FG = new FieldGetter(Combine.PerKey.class);

  private static <K, VI, VA, VO> TransformEvaluator<Combine.PerKey<K, VI, VO>> combinePerKey() {
    return new TransformEvaluator<Combine.PerKey<K, VI, VO>>() {
      @Override
      public void evaluate(Combine.PerKey<K, VI, VO> transform, EvaluationContext context) {
        final Combine.KeyedCombineFn<K, VI, VA, VO> keyed =
            COMBINE_PERKEY_FG.get("fn", transform);
        @SuppressWarnings("unchecked")
        JavaRDDLike<KV<K, VI>, ?> inRdd =
            (JavaRDDLike<KV<K, VI>, ?>) context.getInputRDD(transform);

        // We need to duplicate K as both the key of the JavaPairRDD as well as inside the value,
        // since the functions passed to combineByKey don't receive the associated key of each
        // value, and we need to map back into methods in Combine.KeyedCombineFn, which each
        // require the key in addition to the VI's and VA's being merged/accumulated. Once Spark
        // provides a way to include keys in the arguments of combine/merge functions, we won't
        // need to duplicate the keys anymore.
        JavaPairRDD<K, KV<K, VI>> inRddDuplicatedKeyPair = inRdd.mapToPair(
            new PairFunction<KV<K, VI>, K, KV<K, VI>>() {
              @Override
              public Tuple2<K, KV<K, VI>> call(KV<K, VI> kv) {
                return new Tuple2<>(kv.getKey(), kv);
              }
            });

        // The output of combineByKey will be "VA" (accumulator) types rather than "VO" (final
        // output types) since Combine.CombineFn only provides ways to merge VAs, and no way
        // to merge VOs.
        JavaPairRDD<K, KV<K, VA>> accumulated = inRddDuplicatedKeyPair.combineByKey(
            new Function<KV<K, VI>, KV<K, VA>>() {
              @Override
              public KV<K, VA> call(KV<K, VI> input) {
                VA acc = keyed.createAccumulator(input.getKey());
                keyed.addInput(input.getKey(), acc, input.getValue());
                return KV.of(input.getKey(), acc);
              }
            },
            new Function2<KV<K, VA>, KV<K, VI>, KV<K, VA>>() {
              @Override
              public KV<K, VA> call(KV<K, VA> acc, KV<K, VI> input) {
                keyed.addInput(acc.getKey(), acc.getValue(), input.getValue());
                return acc;
              }
            },
            new Function2<KV<K, VA>, KV<K, VA>, KV<K, VA>>() {
              @Override
              public KV<K, VA> call(KV<K, VA> acc1, KV<K, VA> acc2) {
                return KV.of(
                    acc1.getKey(),
                    keyed.mergeAccumulators(
                        acc1.getKey(), ImmutableList.of(acc1.getValue(), acc2.getValue())));
              }
            });

        JavaPairRDD<K, VO> extracted = accumulated.mapValues(
            new Function<KV<K, VA>, VO>() {
              @Override
              public VO call(KV<K, VA> acc) {
                return keyed.extractOutput(acc.getKey(), acc.getValue());
              }
            });
        context.setOutputRDD(transform, fromPair(extracted));
      }
    };
  }

  private static final class KVFunction<K, V> implements Function<KV<K, Iterable<V>>, KV<K, V>> {
    private final Combine.KeyedCombineFn<K, V, ?, V> keyed;

    KVFunction(Combine.KeyedCombineFn<K, V, ?, V> keyed) {
      this.keyed = keyed;
    }

    @Override
    public KV<K, V> call(KV<K, Iterable<V>> kv) throws Exception {
      return KV.of(kv.getKey(), keyed.apply(kv.getKey(), kv.getValue()));
    }
  }

  private static <K, V> JavaPairRDD<K, V> toPair(JavaRDDLike<KV<K, V>, ?> rdd) {
    return rdd.mapToPair(new PairFunction<KV<K, V>, K, V>() {
      @Override
      public Tuple2<K, V> call(KV<K, V> kv) {
        return new Tuple2<>(kv.getKey(), kv.getValue());
      }
    });
  }

  private static <K, V> JavaRDDLike<KV<K, V>, ?> fromPair(JavaPairRDD<K, V> rdd) {
    return rdd.map(new Function<Tuple2<K, V>, KV<K, V>>() {
      @Override
      public KV<K, V> call(Tuple2<K, V> t2) {
        return KV.of(t2._1(), t2._2());
      }
    });
  }

  private static <I, O> TransformEvaluator<ParDo.Bound<I, O>> parDo() {
    return new TransformEvaluator<ParDo.Bound<I, O>>() {
      @Override
      public void evaluate(ParDo.Bound<I, O> transform, EvaluationContext context) {
        DoFnFunction<I, O> dofn =
            new DoFnFunction<>(transform.getFn(),
                context.getRuntimeContext(),
                getSideInputs(transform.getSideInputs(), context));
        @SuppressWarnings("unchecked")
        JavaRDDLike<I, ?> inRDD = (JavaRDDLike<I, ?>) context.getInputRDD(transform);
        context.setOutputRDD(transform, inRDD.mapPartitions(dofn));
      }
    };
  }

  private static final FieldGetter MULTIDO_FG = new FieldGetter(ParDo.BoundMulti.class);

  private static <I, O> TransformEvaluator<ParDo.BoundMulti<I, O>> multiDo() {
    return new TransformEvaluator<ParDo.BoundMulti<I, O>>() {
      @Override
      public void evaluate(ParDo.BoundMulti<I, O> transform, EvaluationContext context) {
        TupleTag<O> mainOutputTag = MULTIDO_FG.get("mainOutputTag", transform);
        MultiDoFnFunction<I, O> multifn = new MultiDoFnFunction<>(
            transform.getFn(),
            context.getRuntimeContext(),
            mainOutputTag,
            getSideInputs(transform.getSideInputs(), context));

        @SuppressWarnings("unchecked")
        JavaRDDLike<I, ?> inRDD = (JavaRDDLike<I, ?>) context.getInputRDD(transform);
        JavaPairRDD<TupleTag<?>, Object> all = inRDD
            .mapPartitionsToPair(multifn)
            .cache();

        PCollectionTuple pct = context.getOutput(transform);
        for (Map.Entry<TupleTag<?>, PCollection<?>> e : pct.getAll().entrySet()) {
          @SuppressWarnings("unchecked")
          JavaPairRDD<TupleTag<?>, Object> filtered =
              all.filter(new TupleTagFilter(e.getKey()));
          context.setRDD(e.getValue(), filtered.values());
        }
      }
    };
  }


  private static <T> TransformEvaluator<TextIO.Read.Bound<T>> readText() {
    return new TransformEvaluator<TextIO.Read.Bound<T>>() {
      @Override
      public void evaluate(TextIO.Read.Bound<T> transform, EvaluationContext context) {
        String pattern = transform.getFilepattern();
        JavaRDD<String> rdd = context.getSparkContext().textFile(pattern);
        context.setOutputRDD(transform, rdd);
      }
    };
  }

  private static <T> TransformEvaluator<TextIO.Write.Bound<T>> writeText() {
    return new TransformEvaluator<TextIO.Write.Bound<T>>() {
      @Override
      public void evaluate(TextIO.Write.Bound<T> transform, EvaluationContext context) {
        @SuppressWarnings("unchecked")
        JavaRDDLike<T, ?> last = (JavaRDDLike<T, ?>) context.getInputRDD(transform);
        String pattern = transform.getFilenamePrefix();
        last.saveAsTextFile(pattern);
      }
    };
  }

  private static <T> TransformEvaluator<AvroIO.Read.Bound<T>> readAvro() {
    return new TransformEvaluator<AvroIO.Read.Bound<T>>() {
      @Override
      public void evaluate(AvroIO.Read.Bound<T> transform, EvaluationContext context) {
        String pattern = transform.getFilepattern();
        JavaRDD<?> rdd = context.getSparkContext()
            .newAPIHadoopFile(pattern, AvroKeyInputFormat.class, AvroKey.class,
                NullWritable.class, new Configuration())
            .map(new Function<Tuple2<AvroKey, NullWritable>, Object>() {
              @Override
              public Object call(Tuple2<AvroKey, NullWritable> t) throws Exception {
                return t._1().datum();
              }
            });
        context.setOutputRDD(transform, rdd);
      }
    };
  }

  private static <T> TransformEvaluator<AvroIO.Write.Bound<T>> writeAvro() {
    return new TransformEvaluator<AvroIO.Write.Bound<T>>() {
      @Override
      public void evaluate(AvroIO.Write.Bound<T> transform, EvaluationContext context) {
        @SuppressWarnings("unchecked")
        String pattern = transform.getFilenamePrefix();
        JavaRDDLike<T, ?> last = (JavaRDDLike<T, ?>) context.getInputRDD(transform);
        Job job;
        try {
          job = Job.getInstance();
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
        AvroJob.setOutputKeySchema(job, transform.getSchema());
        last.mapToPair(new PairFunction<T, AvroKey<T>, NullWritable>() {
            @Override
            public Tuple2<AvroKey<T>, NullWritable> call(T t) throws Exception {
              return new Tuple2<>(new AvroKey<>(t), NullWritable.get());
            }})
          .saveAsNewAPIHadoopFile(pattern, AvroKey.class, NullWritable.class,
              AvroKeyOutputFormat.class, job.getConfiguration());

      }
    };
  }

  private static <T> TransformEvaluator<Create<T>> create() {
    return new TransformEvaluator<Create<T>>() {
      @Override
      public void evaluate(Create<T> transform, EvaluationContext context) {
        Iterable<T> elems = transform.getElements();
        Coder<T> coder = context.getOutput(transform).getCoder();
        JavaRDD<byte[]> rdd = context.getSparkContext().parallelize(
            CoderHelpers.toByteArrays(elems, coder));
        context.setOutputRDD(transform, rdd.map(CoderHelpers.fromByteFunction(coder)));
      }
    };
  }

  private static <T> TransformEvaluator<View.AsSingleton<T>> viewAsSingleton() {
    return new TransformEvaluator<View.AsSingleton<T>>() {
      @Override
      public void evaluate(View.AsSingleton<T> transform, EvaluationContext context) {
        //PROBABLY INCORRECT. Fix it.
        Iterable<T> input = context.get(context.getInput(transform));
        context.setPView(context.getOutput(transform), Iterables.transform(input,
            new WindowingFunction<T>()));
      }
    };
  }

  private static <T> TransformEvaluator<View.AsIterable<T>> viewAsIter() {
    return new TransformEvaluator<View.AsIterable<T>>() {
      @Override
      public void evaluate(View.AsIterable<T> transform, EvaluationContext context) {
        Iterable<T> input = context.get(context.getInput(transform));

        context.setPView(context.getOutput(transform), Iterables.transform(input,
            new WindowingFunction<T>()));
      }
    };
  }

  private static <R, W> TransformEvaluator<View.CreatePCollectionView<R, W>> createPCollView() {
    return new TransformEvaluator<View.CreatePCollectionView<R, W>>() {
      @Override
      public void evaluate(View.CreatePCollectionView<R, W> transform, EvaluationContext
          context) {
        Iterable<WindowedValue<?>> iter = Iterables.transform(context.get(context.getInput
                (transform)), new WindowingFunction<R>()
        );

        context.setPView(context.getOutput(transform), iter);
      }
    };
  }

  private static class WindowingFunction<R> implements com.google.common.base.Function<R,
      WindowedValue<?>> {
    @Override
    public WindowedValue<R> apply(R t) {
      return WindowedValue.valueInGlobalWindow(t);
    }
  }

  private static class TupleTagFilter<V> implements Function<Tuple2<TupleTag<V>, Object>, Boolean> {
    private final TupleTag<V> tag;

    private TupleTagFilter(TupleTag<V> tag) {
      this.tag = tag;
    }

    @Override
    public Boolean call(Tuple2<TupleTag<V>, Object> input) {
      return tag.equals(input._1());
    }
  }

  private static Map<TupleTag<?>, BroadcastHelper<?>> getSideInputs(
      List<PCollectionView<?>> views,
      EvaluationContext context) {
    if (views == null) {
      return ImmutableMap.of();
    } else {
      Map<TupleTag<?>, BroadcastHelper<?>> sideInputs = Maps.newHashMap();
      for (PCollectionView<?> view : views) {
        Object sideinput = view.fromIterableInternal(context.getPCollectionView(view));
        Coder<Object> coder = context.getDefaultCoder(sideinput);
        BroadcastHelper<?> helper = new BroadcastHelper<>(sideinput, coder);
        //broadcast side inputs
        helper.broadcast(context.getSparkContext());
        sideInputs.put(view.getTagInternal(), helper);
      }
      return sideInputs;
    }
  }

  private static final Map<Class<? extends PTransform>, TransformEvaluator<?>> mEvaluators = Maps
      .newHashMap();

  static {
    mEvaluators.put(TextIO.Read.Bound.class, readText());
    mEvaluators.put(TextIO.Write.Bound.class, writeText());
    mEvaluators.put(AvroIO.Read.Bound.class, readAvro());
    mEvaluators.put(AvroIO.Write.Bound.class, writeAvro());
    mEvaluators.put(ParDo.Bound.class, parDo());
    mEvaluators.put(ParDo.BoundMulti.class, multiDo());
    mEvaluators.put(GroupByKey.GroupByKeyOnly.class, gbk());
    mEvaluators.put(Combine.GroupedValues.class, grouped());
    mEvaluators.put(Combine.PerKey.class, combinePerKey());
    mEvaluators.put(Flatten.FlattenPCollectionList.class, flattenPColl());
    mEvaluators.put(Create.class, create());
    mEvaluators.put(View.AsSingleton.class, viewAsSingleton());
    mEvaluators.put(View.AsIterable.class, viewAsIter());
    mEvaluators.put(View.CreatePCollectionView.class, createPCollView());
  }

  public static <PT extends PTransform> boolean hasTransformEvaluator(Class<PT> clazz) {
    return mEvaluators.containsKey(clazz);
  }

  public static <PT extends PTransform> TransformEvaluator<PT> getTransformEvaluator(Class<PT>
                                                                                         clazz) {
    @SuppressWarnings("unchecked")
    TransformEvaluator<PT> transform = (TransformEvaluator<PT>) mEvaluators.get(clazz);
    if (transform == null) {
      throw new IllegalStateException("No TransformEvaluator registered for " + clazz);
    }
    return transform;
  }
}
