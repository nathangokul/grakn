/*
 * Grakn - A Distributed Semantic Database
 * Copyright (C) 2016  Grakn Labs Limited
 *
 * Grakn is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Grakn is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Grakn. If not, see <http://www.gnu.org/licenses/gpl.txt>.
 */

package ai.grakn.graql.internal.query.analytics;

import ai.grakn.GraknGraph;
import ai.grakn.concept.ResourceType;
import ai.grakn.concept.Type;
import ai.grakn.concept.TypeLabel;
import ai.grakn.exception.GraqlQueryException;
import ai.grakn.graql.Graql;
import ai.grakn.graql.Pattern;
import ai.grakn.graql.internal.util.StringConverter;
import ai.grakn.util.Schema;
import com.google.common.collect.Sets;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static ai.grakn.graql.Graql.or;
import static ai.grakn.graql.Graql.var;
import static java.util.stream.Collectors.joining;

abstract class AbstractStatisticsQuery<T> extends AbstractComputeQuery<T> {

    Set<TypeLabel> statisticsResourceTypeLabels = new HashSet<>();
    private final Map<TypeLabel, ResourceType.DataType> resourceTypesDataTypeMap = new HashMap<>();

    AbstractStatisticsQuery<T> setStatisticsResourceType(String... statisticsResourceTypeLabels) {
        this.statisticsResourceTypeLabels = Arrays.stream(statisticsResourceTypeLabels).map(TypeLabel::of).collect(Collectors.toSet());
        return this;
    }

    AbstractStatisticsQuery<T> setStatisticsResourceType(Collection<TypeLabel> statisticsResourceTypeLabels) {
        this.statisticsResourceTypeLabels = Sets.newHashSet(statisticsResourceTypeLabels);
        return this;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    void initSubGraph() {
        super.initSubGraph();
        getResourceTypes(graph.get());
    }

    @Override
    final String graqlString() {
        return getName() + resourcesString() + subtypeString();
    }

    abstract String getName();

    private String resourcesString() {
        return " of " + statisticsResourceTypeLabels.stream()
                .map(StringConverter::typeLabelToString).collect(joining(", "));
    }

    private void getResourceTypes(GraknGraph graph) {
        if (statisticsResourceTypeLabels.isEmpty()) {
            throw GraqlQueryException.statisticsResourceTypesNotSpecified();
        }

        Set<Type> statisticsResourceTypes = statisticsResourceTypeLabels.stream().map((label) -> {
            Type type = graph.getType(label);
            if (type == null) throw GraqlQueryException.labelNotFound(label);
            return type;
        }).collect(Collectors.toSet());
        for (Type type : statisticsResourceTypes) {
            type.subTypes().forEach(subtype -> this.statisticsResourceTypeLabels.add(subtype.getLabel()));
        }

        ResourceType<?> metaResourceType = graph.admin().getMetaResourceType();
        metaResourceType.subTypes().stream()
                .filter(type -> !type.equals(metaResourceType))
                .forEach(type -> resourceTypesDataTypeMap
                        .put(type.asType().getLabel(), type.asResourceType().getDataType()));
    }

    ResourceType.DataType getDataTypeOfSelectedResourceTypes(Set<TypeLabel> resourceTypes) {
        assert resourceTypes != null && !resourceTypes.isEmpty();

        ResourceType.DataType dataType = null;
        for (TypeLabel resourceType : resourceTypes) {
            // check if the selected type is a resource-type
            if (!resourceTypesDataTypeMap.containsKey(resourceType)) {
                throw GraqlQueryException.mustBeResourceType(resourceType);
            }

            if (dataType == null) {
                // check if the resource-type has data-type LONG or DOUBLE
                dataType = resourceTypesDataTypeMap.get(resourceType);

                if (!dataType.equals(ResourceType.DataType.LONG) &&
                        !dataType.equals(ResourceType.DataType.DOUBLE)) {
                    throw GraqlQueryException.resourceMustBeANumber(dataType, resourceType);
                }

            } else {
                // check if all the resource-types have the same data-type
                if (!dataType.equals(resourceTypesDataTypeMap.get(resourceType))) {
                    throw GraqlQueryException.resourcesWithDifferentDataTypes(resourceTypes);
                }
            }
        }
        return dataType;
    }

    boolean selectedResourceTypesHaveInstance(Set<TypeLabel> statisticsResourceTypes) {
        List<Pattern> checkResourceTypes = statisticsResourceTypes.stream()
                .map(type -> var("x").has(type, var())).collect(Collectors.toList());
        List<Pattern> checkSubtypes = subTypeLabels.stream()
                .map(type -> var("x").isa(Graql.label(type))).collect(Collectors.toList());

        return graph.orElseThrow(GraqlQueryException::noGraph).graql().infer(false).match(or(checkResourceTypes), or(checkSubtypes)).ask().execute();
    }

    Set<TypeLabel> getCombinedSubTypes() {
        Set<TypeLabel> allSubTypes = statisticsResourceTypeLabels.stream()
                .map(Schema.ImplicitType.HAS::getLabel).collect(Collectors.toSet());
        allSubTypes.addAll(subTypeLabels);
        allSubTypes.addAll(statisticsResourceTypeLabels);
        return allSubTypes;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;

        AbstractStatisticsQuery<?> that = (AbstractStatisticsQuery<?>) o;

        return statisticsResourceTypeLabels.equals(that.statisticsResourceTypeLabels) &&
                resourceTypesDataTypeMap.equals(that.resourceTypesDataTypeMap);
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + statisticsResourceTypeLabels.hashCode();
        result = 31 * result + resourceTypesDataTypeMap.hashCode();
        return result;
    }
}
