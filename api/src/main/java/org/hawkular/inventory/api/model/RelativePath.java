/*
 * Copyright 2015 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.hawkular.inventory.api.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * A relative path is used in the API to refer to other entities during association. Its precise meaning is
 * context-sensitive but the basic idea is that given a position in the graph, you want to refer to other entities that
 * are "near" without needing to provide their full canonical path.
 *
 * <p>I.e. it is quite usual only associate resources and metrics from a single environment. It would be cumbersome to
 * require the full canonical path for every metric one wants to associate with a resource. Therefore a partial path is
 * used to refer to the metric.
 *
 * <p>The relative path contains one special segment type - encoded as ".." and represented using the
 * {@link org.hawkular.inventory.api.model.RelativePath.Up} class that can be used to go up in the relative path.
 *
 * @author Lukas Krejci
 * @since 0.1.0
 */
public final class RelativePath extends Path implements Serializable {

    public static final Map<String, Class<?>> SHORT_NAME_TYPES = new HashMap<>();
    public static final Map<Class<?>, String> SHORT_TYPE_NAMES = new HashMap<>();
    private static final Map<Class<?>, List<Class<?>>> VALID_PROGRESSIONS = new HashMap<>();

    private static final List<Class<?>> ALL_VALID_TYPES = Arrays.asList(Tenant.class, ResourceType.class,
            MetricType.class, Environment.class, Feed.class, Metric.class, Resource.class, Up.class);

    static {

        SHORT_NAME_TYPES.putAll(CanonicalPath.SHORT_NAME_TYPES);
        SHORT_NAME_TYPES.put("..", Up.class);

        SHORT_TYPE_NAMES.putAll(CanonicalPath.SHORT_TYPE_NAMES);
        SHORT_TYPE_NAMES.put(Up.class, "..");

        List<Class<?>> justUp = Collections.singletonList(Up.class);

        VALID_PROGRESSIONS.put(Tenant.class, Arrays.asList(Environment.class, MetricType.class, ResourceType.class,
                Up.class));
        VALID_PROGRESSIONS.put(Environment.class, Arrays.asList(Metric.class, Resource.class, Feed.class, Up.class));
        VALID_PROGRESSIONS.put(Feed.class, Arrays.asList(Metric.class, Resource.class, Up.class));
        VALID_PROGRESSIONS.put(Resource.class, Arrays.asList(Resource.class, Up.class));
        VALID_PROGRESSIONS.put(null, Arrays.asList(Tenant.class, Relationship.class, Up.class));
        VALID_PROGRESSIONS.put(Metric.class, justUp);
        VALID_PROGRESSIONS.put(ResourceType.class, justUp);
        VALID_PROGRESSIONS.put(MetricType.class, justUp);
    }

    private RelativePath(int start, int end, List<Segment> segments) {
        super(start, end, segments);
    }

    @JsonCreator
    public static RelativePath fromString(String path) {
        return fromPartiallyUntypedString(path, null);
    }

    /**
     * @param path         the relative path to parse
     * @param typeProvider the type provider used to figure out types of segments that don't explicitly mention it
     * @return the parsed relative path
     * @see Path#fromPartiallyUntypedString(String, TypeProvider)
     */
    public static RelativePath fromPartiallyUntypedString(String path, TypeProvider typeProvider) {
        return (RelativePath) Path.fromString(path, false, SHORT_NAME_TYPES, Extender::new,
                new RelativeTypeProvider(typeProvider));
    }

    /**
     * An overload of {@link #fromPartiallyUntypedString(String, TypeProvider)} which uses the provided initial position
     * to figure out the possible type if is missing in the provided relative path.
     *
     * @param path              the relative path to parse
     * @param initialPosition   the initial position using which the types will be deduced for the segments that don't
     *                          specify the type explicitly
     * @param intendedFinalType the type of the final segment in the path. This can resolve potentially ambiguous
     *                          situations where, given the initial position, more choices are possible.
     * @return the parsed relative path
     */
    public static RelativePath fromPartiallyUntypedString(String path, CanonicalPath initialPosition,
            Class<?> intendedFinalType) {

        return (RelativePath) Path.fromString(path, false, SHORT_NAME_TYPES,
                (from, segments) -> new Extender(from, segments, (segs) -> {
                    RelativePath.Extender tmp = RelativePath.empty();
                    segs.forEach(tmp::extend);

                    CanonicalPath full = segs.isEmpty() ? initialPosition : tmp.get().applyTo(initialPosition);

                    if (!full.isDefined()) {
                        return Arrays.asList(Tenant.class, Relationship.class);
                    } else {
                        return VALID_PROGRESSIONS.get(full.getSegment().getElementType());
                    }
                }), new RelativeTypeProvider(new TypeProvider() {
                    RelativePath.Extender currentPath = RelativePath.empty();
                    int currentLength;

                    @Override
                    public void segmentParsed(Segment segment) {
                        currentPath.extend(segment);
                        currentLength++;
                    }

                    @Override
                    public Segment deduceSegment(String type, String id, boolean isLast) {
                        if (type != null && !type.isEmpty()) {
                            //we're here only to figure out what the default handler couldn't, if there was a type
                            //the default handler should have figured it out and we have no additional information
                            //to resolve the situation.
                            return null;
                        }

                        CanonicalPath full = currentLength == 0 ? initialPosition : currentPath.get()
                                .applyTo(initialPosition);

                        Class<?> nextStep = unambiguousPathNextStep(intendedFinalType,
                                full.getSegment().getElementType(), isLast, new HashMap<>());

                        if (nextStep != null) {
                            return new Segment(nextStep, id);
                        }

                        return null;
                    }

                    @Override
                    public void finished() {
                    }

                    private Class<?> unambiguousPathNextStep(Class<?> targetType, Class<?> currentType,
                            boolean isLast, Map<Class<?>, Boolean> visitedTypes) {

                        if (targetType.equals(currentType)) {
                            return targetType;
                        }

                        Set<Class<?>> ret = new HashSet<>();

                        fillPossiblePathsToTarget(targetType, currentType, ret, visitedTypes, true);

                        if (ret.size() == 0) {
                            return null;
                        } else if (ret.size() == 1) {
                            return ret.iterator().next();
                        } else if (isLast) {
                            //there are multiple progressions to the intended type possible, but we're processing the
                            //last path segment. So if one of the possible progressions is the intended type itself,
                            //we're actually good.
                            if (ret.contains(intendedFinalType)) {
                                return intendedFinalType;
                            }
                        }

                        throw new IllegalArgumentException("Cannot unambiguously deduce types of the untyped path" +
                                " segments.");
                    }

                    private boolean fillPossiblePathsToTarget(Class<?> targetType, Class<?> currentType,
                            Set<Class<?>> result, Map<Class<?>, Boolean> visitedTypes, boolean isStart) {

                        if (targetType.equals(currentType)) {
                            if (isStart) {
                                result.add(currentType);
                            }
                            return true;
                        }

                        List<Class<?>> options = CanonicalPath.VALID_PROGRESSIONS.get(currentType);

                        if (options == null || options.isEmpty()) {
                            return false;
                        }

                        boolean matched = false;

                        for (Class<?> option : options) {
                            if (!visitedTypes.containsKey(option)) {
                                visitedTypes.put(option, false);

                                if (fillPossiblePathsToTarget(targetType, option, result, visitedTypes, false)) {
                                    if (isStart) {
                                        result.add(option);
                                    }

                                    visitedTypes.put(option, true);
                                    matched = true;
                                }
                            } else {
                                matched |= visitedTypes.get(option);
                            }
                        }

                        return matched;
                    }
                }));
    }

    @Override
    protected Path newInstance(int startIdx, int endIdx, List<Segment> segments) {
        return new RelativePath(startIdx, endIdx, segments);
    }

    /**
     * @return an empty canonical path to be extended
     */
    public static Extender empty() {
        return new Extender(0, new ArrayList<>());
    }

    public static Builder to() {
        return new Builder(new ArrayList<>());
    }

    /**
     * Applies this relative path on the provided canonical path.
     *
     * @param path
     */
    public CanonicalPath applyTo(CanonicalPath path) {
        CanonicalPath.Extender extender = new CanonicalPath.Extender(0, new ArrayList<>(path.getPath())) {
            @Override
            public CanonicalPath.Extender extend(Segment segment) {
                if (Up.class.equals(segment.getElementType())) {
                    segments.remove(segments.size() - 1);
                } else {
                    super.extend(segment);
                }

                return this;
            }
        };

        getPath().forEach(extender::extend);

        return extender.get();
    }

    @SuppressWarnings("unchecked")
    @Override
    public Iterator<RelativePath> ascendingIterator() {
        return (Iterator<RelativePath>) super.ascendingIterator();
    }

    @SuppressWarnings("unchecked")
    @Override
    public Iterator<RelativePath> descendingIterator() {
        return (Iterator<RelativePath>) super.descendingIterator();
    }

    @Override
    public RelativePath down() {
        return (RelativePath) super.down();
    }

    @Override
    public RelativePath down(int distance) {
        return (RelativePath) super.down(distance);
    }

    @Override
    public RelativePath up() {
        return (RelativePath) super.up();
    }

    @Override
    public RelativePath up(int distance) {
        return (RelativePath) super.up(distance);
    }

    @JsonValue
    @Override
    public String toString() {
        return new Encoder(SHORT_TYPE_NAMES, (s) -> !Up.class.equals(s.getElementType())).encode("", this);
    }

    public static final class Up {
        private Up() {
        }
    }

    public static class Builder extends Path.Builder<RelativePath> {

        Builder(List<Segment> segments) {
            super(segments, RelativePath::new);
        }

        public TenantBuilder tenant(String id) {
            segments.add(new Segment(Tenant.class, id));
            return new TenantBuilder(segments);
        }

        public RelationshipBuilder relationship(String id) {
            segments.add(new Segment(Relationship.class, id));
            return new RelationshipBuilder(segments);
        }

        public EnvironmentBuilder environment(String id) {
            segments.add(new Segment(Environment.class, id));
            return new EnvironmentBuilder(segments);
        }

        public ResourceTypeBuilder resourceType(String id) {
            segments.add(new Segment(ResourceType.class, id));
            return new ResourceTypeBuilder(segments);
        }

        public MetricTypeBuilder metricType(String id) {
            segments.add(new Segment(MetricType.class, id));
            return new MetricTypeBuilder(segments);
        }

        public FeedBuilder feed(String id) {
            segments.add(new Segment(Feed.class, id));
            return new FeedBuilder(segments);
        }

        public ResourceBuilder resource(String id) {
            segments.add(new Segment(Resource.class, id));
            return new ResourceBuilder(segments);
        }

        public MetricBuilder metric(String id) {
            segments.add(new Segment(Metric.class, id));
            return new MetricBuilder(segments);
        }

        public UpBuilder up() {
            segments.add(new Segment(Up.class, null));
            return new UpBuilder(segments);
        }
    }

    public static class RelationshipBuilder extends Path.RelationshipBuilder<RelativePath> {
        RelationshipBuilder(List<Segment> segments) {
            super(segments, RelativePath::new);
        }
    }

    public static class TenantBuilder extends Path.TenantBuilder<RelativePath> {

        TenantBuilder(List<Segment> segments) {
            super(segments, RelativePath::new);
        }

        public EnvironmentBuilder environment(String id) {
            segments.add(new Segment(Environment.class, id));
            return new EnvironmentBuilder(segments);
        }

        public ResourceTypeBuilder resourceType(String id) {
            segments.add(new Segment(ResourceType.class, id));
            return new ResourceTypeBuilder(segments);
        }

        public MetricTypeBuilder metricType(String id) {
            segments.add(new Segment(MetricType.class, id));
            return new MetricTypeBuilder(segments);
        }

        public UpBuilder up() {
            segments.add(new Segment(Up.class, null));
            return new UpBuilder(segments);
        }
    }

    public static class ResourceTypeBuilder extends Path.ResourceTypeBuilder<RelativePath> {
        ResourceTypeBuilder(List<Segment> segments) {
            super(segments, RelativePath::new);
        }
    }

    public static class MetricTypeBuilder extends Path.MetricTypeBuilder<RelativePath> {
        MetricTypeBuilder(List<Segment> segments) {
            super(segments, RelativePath::new);
        }
    }

    public static class EnvironmentBuilder extends Path.EnvironmentBuilder<RelativePath> {
        EnvironmentBuilder(List<Segment> segments) {
            super(segments, RelativePath::new);
        }

        public FeedBuilder feed(String id) {
            segments.add(new Segment(Feed.class, id));
            return new FeedBuilder(segments);
        }

        public ResourceBuilder resource(String id) {
            segments.add(new Segment(Resource.class, id));
            return new ResourceBuilder(segments);
        }

        public MetricBuilder metric(String id) {
            segments.add(new Segment(Metric.class, id));
            return new MetricBuilder(segments);
        }
    }

    public static class FeedBuilder extends Path.FeedBuilder<RelativePath> {

        FeedBuilder(List<Segment> segments) {
            super(segments, RelativePath::new);
        }

        public ResourceBuilder resource(String id) {
            segments.add(new Segment(Resource.class, id));
            return new ResourceBuilder(segments);
        }

        public MetricBuilder metric(String id) {
            segments.add(new Segment(Metric.class, id));
            return new MetricBuilder(segments);
        }
    }

    public static class ResourceBuilder extends Path.ResourceBuilder<RelativePath> {

        ResourceBuilder(List<Segment> segments) {
            super(segments, RelativePath::new);
        }

        public ResourceBuilder resource(String id) {
            segments.add(new Segment(Resource.class, id));
            return this;
        }
    }

    public static class MetricBuilder extends Path.MetricBuilder<RelativePath> {

        MetricBuilder(List<Segment> segments) {
            super(segments, RelativePath::new);
        }
    }

    public static class UpBuilder extends AbstractBuilder<RelativePath> {
        UpBuilder(List<Segment> segments) {
            super(segments, RelativePath::new);
        }

        public TenantBuilder tenant(String id) {
            segments.add(new Segment(Tenant.class, id));
            return new TenantBuilder(segments);
        }

        public EnvironmentBuilder environment(String id) {
            segments.add(new Segment(Environment.class, id));
            return new EnvironmentBuilder(segments);
        }

        public ResourceTypeBuilder resourceType(String id) {
            segments.add(new Segment(ResourceType.class, id));
            return new ResourceTypeBuilder(segments);
        }

        public MetricTypeBuilder metricType(String id) {
            segments.add(new Segment(MetricType.class, id));
            return new MetricTypeBuilder(segments);
        }

        public FeedBuilder feed(String id) {
            segments.add(new Segment(Feed.class, id));
            return new FeedBuilder(segments);
        }

        public ResourceBuilder resource(String id) {
            segments.add(new Segment(Resource.class, id));
            return new ResourceBuilder(segments);
        }

        public MetricBuilder metric(String id) {
            segments.add(new Segment(Metric.class, id));
            return new MetricBuilder(segments);
        }

        public UpBuilder up() {
            segments.add(new Segment(Up.class, null));
            return this;
        }

        public RelativePath get() {
            return constructor.create(0, segments.size(), segments);
        }
    }

    public static class Extender extends Path.Extender {

        Extender(int from, List<Segment> segments) {
            this(from, segments, (segs) -> {
                if (segs.isEmpty()) {
                    return ALL_VALID_TYPES;
                }

                Class<?> lastType = segs.get(segs.size() - 1).getElementType();

                if (Up.class.equals(lastType)) {
                    int idx = segs.size() - 2;
                    while (idx >= 0 && Up.class.equals(segs.get(idx).getElementType())) {
                        idx--;
                    }

                    if (idx < 0) {
                        return ALL_VALID_TYPES;
                    } else if (idx >= 0) {
                        lastType = segs.get(idx).getElementType();
                    }
                }

                return VALID_PROGRESSIONS.get(lastType);
            });
        }

        Extender(int from, List<Segment> segments, Function<List<Segment>, List<Class<?>>> validProgressions) {
            super(from, segments, validProgressions);
        }

        @Override
        protected RelativePath newPath(int startIdx, int endIdx, List<Segment> segments) {
            return new RelativePath(startIdx, endIdx, segments);
        }

        @Override
        public Extender extend(Segment segment) {
            return (Extender) super.extend(segment);
        }

        @Override
        public Extender extend(Class<? extends AbstractElement<?, ?>> type, String id) {
            return (Extender) super.extend(type, id);
        }

        @Override
        public RelativePath get() {
            return (RelativePath) super.get();
        }
    }

    private static class RelativeTypeProvider extends EnhancedTypeProvider {
        private final TypeProvider wrapped;

        private RelativeTypeProvider(TypeProvider wrapped) {
            this.wrapped = wrapped;
        }

        @Override
        public void segmentParsed(Segment segment) {
            if (wrapped != null) {
                wrapped.segmentParsed(segment);
            }
        }

        @Override
        public Segment deduceSegment(String type, String id, boolean isLast) {
            if (type != null && !type.isEmpty()) {
                Class<?> cls = SHORT_NAME_TYPES.get(type);
                if (!Up.class.equals(cls) && (id == null || id.isEmpty())) {
                    return null;
                } else if (id == null || id.isEmpty()) {
                    return new Segment(cls, null); //cls == up
                } else if (Up.class.equals(cls)) {
                    throw new IllegalArgumentException("The \"up\" path segment cannot have an id.");
                } else {
                    return new Segment(cls, id);
                }
            }

            if (id == null || id.isEmpty()) {
                return null;
            }

            Class<?> cls = SHORT_NAME_TYPES.get(id);
            if (cls == null && wrapped != null) {
                return wrapped.deduceSegment(type, id, isLast);
            } else if (Up.class.equals(cls)) {
                return new Segment(cls, null);
            } else {
                return null;
            }
        }

        @Override
        public void finished() {
            if (wrapped != null) {
                wrapped.finished();
            }
        }

        @Override
        Set<String> getValidTypeName() {
            return SHORT_NAME_TYPES.keySet();
        }
    }
}
