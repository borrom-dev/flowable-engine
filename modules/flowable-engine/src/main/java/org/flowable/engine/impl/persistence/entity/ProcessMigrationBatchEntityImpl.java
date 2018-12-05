/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.flowable.engine.impl.persistence.entity;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.flowable.common.engine.impl.persistence.entity.AbstractEntity;
import org.flowable.engine.runtime.ProcessMigrationBatchPart;

/**
 * @author Dennis Federico
 */
public class ProcessMigrationBatchEntityImpl extends AbstractEntity implements ProcessMigrationBatchEntity, Serializable {

    private static final long serialVersionUID = 1L;

    protected static final String MIGRATION_DOCUMENT_JSON_LABEL = "migrationDocumentJson";

    protected String batchType;
    protected Date createTime;
    protected String sourceProcessDefinitionId;
    protected String targetProcessDefinitionId;
    protected ByteArrayRef migrationDocRefId;
    protected List<ProcessMigrationBatchPart> batchChildren;

    @Override
    public String getIdPrefix() {
        return BpmnEngineEntityConstants.BPMN_ENGINE_ID_PREFIX;
    }

    @Override
    public Object getPersistentState() {
        Map<String, Object> persistentState = new HashMap<>();
        return persistentState;
    }

    @Override
    public String getBatchType() {
        return batchType;
    }

    public void setBatchType(String batchType) {
        this.batchType = batchType;
    }

    @Override
    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date time) {
        this.createTime = time;
    }

    @Override
    public Date getCompleteTime() {

        if (batchChildren != null && !batchChildren.isEmpty()) {
            long maxDate = Long.MIN_VALUE;
            for (ProcessMigrationBatchPart child : batchChildren) {
                if (!child.isCompleted()) {
                    return null;
                }
                maxDate = Long.max(maxDate, child.getCompleteTime().getTime());
            }
            return new Date(maxDate);
        }
        return null;
    }

    @Override
    public boolean isCompleted() {

        if (batchChildren != null && !batchChildren.isEmpty()) {
            return batchChildren.stream().allMatch(ProcessMigrationBatchPart::isCompleted);
        }
        return false;
    }

    @Override
    public String getSourceProcessDefinitionId() {
        return sourceProcessDefinitionId;
    }

    public void setSourceProcessDefinitionId(String sourceProcessDefinitionId) {
        this.sourceProcessDefinitionId = sourceProcessDefinitionId;
    }

    @Override
    public String getTargetProcessDefinitionId() {
        return targetProcessDefinitionId;
    }

    public void setTargetProcessDefinitionId(String targetProcessDefinitionId) {
        this.targetProcessDefinitionId = targetProcessDefinitionId;
    }

    public ByteArrayRef getMigrationDocRefId() {
        return migrationDocRefId;
    }

    public void setMigrationDocRefId(ByteArrayRef migrationDocRefId) {
        this.migrationDocRefId = migrationDocRefId;
    }

    @Override
    public String getMigrationDocumentJson() {
        if (migrationDocRefId != null) {
            byte[] bytes = migrationDocRefId.getBytes();
            if (bytes != null) {
                return new String(bytes, StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    @Override
    public void setMigrationDocumentJson(String migrationDocumentJson) {
        this.migrationDocRefId = setByteArrayRef(this.migrationDocRefId, MIGRATION_DOCUMENT_JSON_LABEL, migrationDocumentJson);
    }

    @Override
    public List<ProcessMigrationBatchPart> getBatchParts() {
        return batchChildren;
    }

    public void addBatchPart(ProcessMigrationBatchPartEntity child) {
        if (batchChildren == null) {
            batchChildren = new ArrayList<>();
        }
        batchChildren.add(child);
    }

    private static ByteArrayRef setByteArrayRef(ByteArrayRef byteArrayRef, String name, String value) {
        if (byteArrayRef == null) {
            byteArrayRef = new ByteArrayRef();
        }
        byte[] bytes = null;
        if (value != null) {
            bytes = value.getBytes(StandardCharsets.UTF_8);
        }
        byteArrayRef.setValue(name, bytes);
        return byteArrayRef;
    }

}

