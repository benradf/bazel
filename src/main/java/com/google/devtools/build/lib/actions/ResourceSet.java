// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.actions;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import com.google.common.primitives.Doubles;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.worker.WorkerKey;
import com.google.devtools.common.options.Converter;
import com.google.devtools.common.options.OptionsParsingException;
import java.util.Iterator;
import java.util.NoSuchElementException;
import javax.annotation.Nullable;

/**
 * Instances of this class represent an estimate of the resource consumption for a particular
 * Action, or the total available resources. We plan to use this to do smarter scheduling of
 * actions, for example making sure that we don't schedule jobs concurrently if they would use so
 * much memory as to cause the machine to thrash.
 */
@Immutable
public class ResourceSet implements ResourceSetOrBuilder {

  /** For actions that consume negligible resources. */
  public static final ResourceSet ZERO = new ResourceSet(0.0, 0.0, 0);

  /** The amount of real memory (resident set size). */
  private final double memoryMb;

  /** The number of CPUs, or fractions thereof. */
  private final double cpuUsage;

  /**
   * Map of extra resources (for example: GPUs, embedded boards, ...) mapping name of the resource
   * to a value.
   */
  private final ImmutableMap<String, Float> extraResourceUsage;

  /** The number of local tests. */
  private final int localTestCount;

  /** The workerKey of used worker. Null if no worker is used. */
  @Nullable private final WorkerKey workerKey;

  private ResourceSet(
      double memoryMb, double cpuUsage, int localTestCount, @Nullable WorkerKey workerKey) {
    this(memoryMb, cpuUsage, ImmutableMap.of(), localTestCount, workerKey);
  }

  private ResourceSet(
      double memoryMb,
      double cpuUsage,
      @Nullable ImmutableMap<String, Float> extraResourceUsage,
      int localTestCount,
      @Nullable WorkerKey workerKey) {
    this.memoryMb = memoryMb;
    this.cpuUsage = cpuUsage;
    this.extraResourceUsage = extraResourceUsage;
    this.localTestCount = localTestCount;
    this.workerKey = workerKey;
  }

  private ResourceSet(double memoryMb, double cpuUsage, int localTestCount) {
    this(memoryMb, cpuUsage, localTestCount, /* workerKey= */ null);
  }

  /**
   * Returns a new ResourceSet with the provided values for memoryMb and cpuUsage, and with 0.0 for
   * ioUsage and localTestCount. Use this method in action resource definitions when they aren't
   * local tests.
   */
  public static ResourceSet createWithRamCpu(double memoryMb, double cpuUsage) {
    if (memoryMb == 0 && cpuUsage == 0) {
      return ZERO;
    }
    return new ResourceSet(memoryMb, cpuUsage, 0);
  }

  /**
   * Returns a new ResourceSet with the provided value for localTestCount, and 0.0 for memoryMb,
   * cpuUsage, and ioUsage. Use this method in action resource definitions when they are local tests
   * that acquire no local resources.
   */
  public static ResourceSet createWithLocalTestCount(int localTestCount) {
    return new ResourceSet(0.0, 0.0, localTestCount);
  }

  /**
   * Returns a new ResourceSet with the provided values for memoryMb, cpuUsage, and localTestCount.
   * Most action resource definitions should use {@link #createWithRamCpu} or {@link
   * #createWithLocalTestCount(int)}. Use this method primarily when constructing ResourceSets that
   * represent available resources.
   */
  public static ResourceSet create(double memoryMb, double cpuUsage, int localTestCount) {
    return ResourceSet.createWithWorkerKey(
        memoryMb, cpuUsage, ImmutableMap.of(), localTestCount, /* workerKey= */ null);
  }

  /**
   * Returns a new ResourceSet with the provided values for memoryMb, cpuUsage, extraResources, and
   * localTestCount. Most action resource definitions should use {@link #createWithRamCpu} or {@link
   * #createWithLocalTestCount(int)}. Use this method primarily when constructing ResourceSets that
   * represent available resources.
   */
  public static ResourceSet create(
      double memoryMb,
      double cpuUsage,
      ImmutableMap<String, Float> extraResourceUsage,
      int localTestCount) {
    return createWithWorkerKey(
        memoryMb, cpuUsage, extraResourceUsage, localTestCount, /* workerKey= */ null);
  }

  public static ResourceSet createWithWorkerKey(
      double memoryMb, double cpuUsage, int localTestCount, WorkerKey workerKey) {
    return ResourceSet.createWithWorkerKey(
        memoryMb, cpuUsage, /* extraResourceUsage= */ ImmutableMap.of(), localTestCount, workerKey);
  }

  public static ResourceSet createWithWorkerKey(
      double memoryMb,
      double cpuUsage,
      ImmutableMap<String, Float> extraResourceUsage,
      int localTestCount,
      WorkerKey workerKey) {
    if (memoryMb == 0
        && cpuUsage == 0
        && extraResourceUsage.size() == 0
        && localTestCount == 0
        && workerKey == null) {
      return ZERO;
    }
    return new ResourceSet(memoryMb, cpuUsage, extraResourceUsage, localTestCount, workerKey);
  }

  public static ResourceSet createFromExecutionInfo(ImmutableMap<String, String> executionInfo) {
    double ram = 0.0, cpu = 0.0;
    for (ImmutableMap.Entry<String, String> entry : executionInfo.entrySet()) {
        String[] parts = entry.getKey().split(":", 2);
        if (parts.length != 2) {
            continue;
        }
        if (parts[0].equals("memory")) {
            if (!parts[1].endsWith("M")) {
                // We only support values in MiB, but require a suffix to avoid confusion.
                throw new IllegalArgumentException("Expected memory value to end with 'M'");
            }
            ram = Integer.parseInt(parts[1].substring(0, parts[1].length() - 1));
            if (ram < 0.0) {
                throw new IllegalArgumentException("Expected memory value to be positive");
            }
        } else if (parts[0].equals("cpu")) {
            cpu = Integer.parseInt(parts[1]);
            if (cpu < 0.0) {
                throw new IllegalArgumentException("Expected cpu value to be positive");
            }
        }
    }
    return createWithRamCpu(ram, cpu);
  }

  /** Returns the amount of real memory (resident set size) used in MB. */
  public double getMemoryMb() {
    return memoryMb;
  }

  /**
   * Returns the workerKey of worker.
   *
   * <p>If there is no worker requested, then returns null
   */
  public WorkerKey getWorkerKey() {
    return workerKey;
  }

  /**
   * Returns the number of CPUs (or fractions thereof) used. For a CPU-bound single-threaded
   * process, this will be 1.0. For a single-threaded process which spends part of its time waiting
   * for I/O, this will be somewhere between 0.0 and 1.0. For a multi-threaded or multi-process
   * application, this may be more than 1.0.
   */
  public double getCpuUsage() {
    return cpuUsage;
  }

  public ImmutableMap<String, Float> getExtraResourceUsage() {
    return extraResourceUsage;
  }

  /** Returns the local test count used. */
  public int getLocalTestCount() {
    return localTestCount;
  }

  @Override
  public String toString() {
    return "Resources: \n"
        + "Memory: "
        + memoryMb
        + "M\n"
        + "CPU: "
        + cpuUsage
        + "\n"
        + Joiner.on("\n").withKeyValueSeparator(": ").join(extraResourceUsage.entrySet())
        + "Local tests: "
        + localTestCount
        + "\n";
  }

  @Override
  public boolean equals(Object that) {
    if (that == null) {
      return false;
    }

    if (!(that instanceof ResourceSet)) {
      return false;
    }

    ResourceSet thatResourceSet = (ResourceSet) that;
    return thatResourceSet.getMemoryMb() == getMemoryMb()
        && thatResourceSet.getCpuUsage() == getCpuUsage()
        && thatResourceSet.localTestCount == getLocalTestCount();
  }

  @Override
  public int hashCode() {
    int p = 239;
    return Doubles.hashCode(getMemoryMb())
        + Doubles.hashCode(getCpuUsage()) * p
        + getLocalTestCount() * p * p;
  }

  /** Converter for {@link ResourceSet}. */
  public static class ResourceSetConverter extends Converter.Contextless<ResourceSet> {
    private static final Splitter SPLITTER = Splitter.on(',');

    @Override
    public ResourceSet convert(String input) throws OptionsParsingException {
      Iterator<String> values = SPLITTER.split(input).iterator();
      try {
        double memoryMb = Double.parseDouble(values.next());
        double cpuUsage = Double.parseDouble(values.next());
        // There used to be a third field here called ioUsage. In order to not break existing users,
        // we keep expecting a third field, which must be a double. In the future, we may accept the
        // two-param variant, and then even phase out the three-param variant.
        Double.parseDouble(values.next());
        if (values.hasNext()) {
          throw new OptionsParsingException("Expected exactly 3 comma-separated float values");
        }
        if (memoryMb <= 0.0 || cpuUsage <= 0.0) {
          throw new OptionsParsingException("All resource values must be positive");
        }
        return create(memoryMb, cpuUsage, Integer.MAX_VALUE);
      } catch (NumberFormatException | NoSuchElementException nfe) {
        throw new OptionsParsingException("Expected exactly 3 comma-separated float values", nfe);
      }
    }

    @Override
    public String getTypeDescription() {
      return "comma-separated available amount of RAM (in MB), CPU (in cores) and "
          + "available I/O (1.0 being average workstation)";
    }
  }

  @Override
  public ResourceSet buildResourceSet(OS os, int inputsSize) throws ExecException {
    return this;
  }

  /** Merge two ResourceSets, taking values from rhs only when lhs values are zero. */
  public static ResourceSet merge(ResourceSet lhs, ResourceSet rhs) {
    ImmutableMap.Builder<String, Float> extraResourceUsageMerged =
        new ImmutableMap.Builder<String, Float>();
    extraResourceUsageMerged.putAll(lhs.extraResourceUsage);
    for (ImmutableMap.Entry<String, Float> rhsEntry : rhs.extraResourceUsage.entrySet()) {
        if (!lhs.extraResourceUsage.containsKey(rhsEntry.getKey())) {
            extraResourceUsageMerged.put(rhsEntry.getKey(), rhsEntry.getValue());
        }
    }
    return new ResourceSet(
        lhs.memoryMb != 0.0 ? lhs.memoryMb : rhs.memoryMb,
        lhs.cpuUsage != 0.0 ? lhs.cpuUsage : rhs.cpuUsage,
        extraResourceUsageMerged.build(),
        lhs.localTestCount != 0 ? lhs.localTestCount : rhs.localTestCount,
        lhs.workerKey != null ? lhs.workerKey : rhs.workerKey);
  }
}
