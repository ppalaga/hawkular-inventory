/*
 * Copyright 2015-2016 Red Hat, Inc. and/or its affiliates
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
package org.hawkular.inventory.json.mixins.model;

import org.hawkular.inventory.api.model.SyncHash;
import org.hawkular.inventory.json.GenericHashTreeDeserializer;
import org.hawkular.inventory.json.GenericHashTreeSerializer;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

/**
 * @author Lukas Krejci
 * @since 0.18.0
 */
@JsonSerialize(using = SyncHashTreeMixin.Serializer.class)
@JsonDeserialize(using = SyncHashTreeMixin.Deserializer.class)
public class SyncHashTreeMixin {
    public static final class Serializer extends GenericHashTreeSerializer<SyncHash.Tree, String> {}


    public static final class Deserializer extends GenericHashTreeDeserializer<SyncHash.Tree, String> {
        public Deserializer() {
            super(SyncHash.Tree::builder, String.class);
        }
    }
}
