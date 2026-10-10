/*
 *  Copyright 2026 Collate.
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
/*
 *  Copyright 2026 Collate.
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import {
  Button,
  Card,
  Checkbox,
  Empty,
  Form,
  Input,
  Select,
  Space,
  Typography,
} from 'antd';
import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { JsonSchemaObject } from '../../../rest/taskFormSchemasAPI';
import {
  buildDesignerSchema,
  createEmptyDesignerField,
  getDesignerPreviewPayload,
  TaskFormDesignerField,
} from '../../../utils/TaskFormDesignerUtils';
import TaskPayloadSchemaFields from '../../TasksPage/shared/TaskPayloadSchemaFields';

interface TaskFormBuilderSectionProps {
  title: string;
  description?: string;
  fields: TaskFormDesignerField[];
  onChange: (fields: TaskFormDesignerField[]) => void;
  testIdPrefix: string;
  baseFormSchema?: JsonSchemaObject;
  baseUiSchema?: JsonSchemaObject;
}

const FIELD_TYPE_OPTIONS = [
  { label: 'label.task-form-short-text', value: 'shortText' },
  { label: 'label.task-form-long-text', value: 'longText' },
  { label: 'label.number', value: 'number' },
  { label: 'label.task-form-boolean', value: 'boolean' },
  { label: 'label.task-form-single-select', value: 'singleSelect' },
  { label: 'label.multi-select', value: 'multiSelect' },
  { label: 'label.task-form-json', value: 'json' },
];

const JSON_SCHEMA_TYPE_OPTIONS = [
  { label: 'label.task-form-object', value: 'object' },
  { label: 'label.task-form-array', value: 'array' },
];

const FIELD_TYPE_LABEL_MAP: Record<TaskFormDesignerField['type'], string> = {
  shortText: 'label.task-form-short-text',
  longText: 'label.task-form-long-text',
  number: 'label.number',
  boolean: 'label.task-form-boolean',
  singleSelect: 'label.task-form-single-select',
  multiSelect: 'label.multi-select',
  json: 'label.task-form-json',
};

const TaskFormBuilderSection = ({
  title,
  description,
  fields,
  onChange,
  testIdPrefix,
  baseFormSchema,
  baseUiSchema,
}: TaskFormBuilderSectionProps) => {
  const { t } = useTranslation();
  const previewConfig = useMemo(
    () => buildDesignerSchema(fields, baseFormSchema, baseUiSchema),
    [baseFormSchema, baseUiSchema, fields]
  );
  const previewPayload = useMemo(
    () => getDesignerPreviewPayload(fields),
    [fields]
  );

  const updateField = (
    index: number,
    updater: (field: TaskFormDesignerField) => TaskFormDesignerField
  ) => {
    onChange(
      fields.map((field, currentIndex) =>
        currentIndex === index ? updater(field) : field
      )
    );
  };

  return (
    <div className="task-form-builder-section">
      <div className="task-form-builder-section__header">
        <div>
          <Typography.Title className="m-b-xs" level={5}>
            {title}
          </Typography.Title>
          {description ? (
            <Typography.Paragraph className="m-b-0 text-grey-muted">
              {description}
            </Typography.Paragraph>
          ) : null}
        </div>
        <Button
          data-testid={`${testIdPrefix}-add-field`}
          icon={<PlusOutlined />}
          onClick={() => onChange([...fields, createEmptyDesignerField()])}>
          {t('label.add-entity', { entity: t('label.field') })}
        </Button>
      </div>

      <div className="task-form-builder-section__workspace">
        <div className="task-form-builder-section__fields">
          {fields.length ? (
            fields.map((field, index) => {
              const supportsOptions =
                field.type === 'singleSelect' || field.type === 'multiSelect';
              const supportsJsonType = field.type === 'json';
              const summary = [
                t('label.task-form-input-type', {
                  type: t(FIELD_TYPE_LABEL_MAP[field.type]),
                }),
                field.required ? t('label.required') : undefined,
                field.hidden ? t('label.task-form-hidden') : undefined,
              ]
                .filter(Boolean)
                .join(' • ');

              return (
                <Card
                  className="task-form-builder-section__field-card"
                  data-testid={`${testIdPrefix}-field-card-${index}`}
                  extra={
                    <Button
                      danger
                      data-testid={`${testIdPrefix}-field-remove-${index}`}
                      icon={<DeleteOutlined />}
                      size="small"
                      type="text"
                      onClick={() =>
                        onChange(
                          fields.filter(
                            (_, currentIndex) => currentIndex !== index
                          )
                        )
                      }>
                      {t('label.remove')}
                    </Button>
                  }
                  key={field.key}
                  size="small"
                  title={
                    <div className="task-form-builder-section__field-heading">
                      <Typography.Text className="task-form-builder-section__field-title">
                        {field.label ||
                          field.name ||
                          t('label.task-form-numbered-item', {
                            entity: t('label.field'),
                            number: index + 1,
                          })}
                      </Typography.Text>
                      <Typography.Text className="task-form-builder-section__field-meta">
                        {summary}
                      </Typography.Text>
                    </div>
                  }>
                  <div className="d-grid gap-3">
                    <div className="task-form-builder-section__field-grid">
                      <Form.Item
                        className="m-b-0"
                        label={t('label.field-name')}>
                        <Input
                          data-testid={`${testIdPrefix}-field-name-${index}`}
                          placeholder="requestReason"
                          value={field.name}
                          onChange={(event) =>
                            updateField(index, (currentField) => ({
                              ...currentField,
                              name: event.target.value,
                            }))
                          }
                        />
                      </Form.Item>
                      <Form.Item
                        className="m-b-0"
                        label={t('label.task-form-label')}>
                        <Input
                          data-testid={`${testIdPrefix}-field-label-${index}`}
                          placeholder="Request Reason"
                          value={field.label}
                          onChange={(event) =>
                            updateField(index, (currentField) => ({
                              ...currentField,
                              label: event.target.value,
                            }))
                          }
                        />
                      </Form.Item>
                      <Form.Item
                        className="m-b-0"
                        label={t('label.entity-type-plural', {
                          entity: t('label.field'),
                        })}>
                        <Select
                          data-testid={`${testIdPrefix}-field-type-${index}`}
                          options={FIELD_TYPE_OPTIONS.map((option) => ({
                            ...option,
                            label: t(option.label),
                          }))}
                          value={field.type}
                          onChange={(value) =>
                            updateField(index, (currentField) => ({
                              ...currentField,
                              type: value,
                              schemaType:
                                value === 'json'
                                  ? currentField.schemaType ?? 'object'
                                  : currentField.schemaType,
                              options:
                                value === 'singleSelect' ||
                                value === 'multiSelect'
                                  ? currentField.options
                                  : [],
                            }))
                          }
                        />
                      </Form.Item>
                    </div>

                    {supportsJsonType ? (
                      <Form.Item
                        className="m-b-0"
                        label={t('label.task-form-json-shape')}>
                        <Select
                          data-testid={`${testIdPrefix}-field-schema-type-${index}`}
                          options={JSON_SCHEMA_TYPE_OPTIONS.map((option) => ({
                            ...option,
                            label: t(option.label),
                          }))}
                          value={field.schemaType ?? 'object'}
                          onChange={(value) =>
                            updateField(index, (currentField) => ({
                              ...currentField,
                              schemaType: value,
                            }))
                          }
                        />
                      </Form.Item>
                    ) : null}

                    {supportsOptions ? (
                      <Form.Item
                        className="m-b-0"
                        label={t('label.task-form-options')}>
                        <Select
                          data-testid={`${testIdPrefix}-field-options-${index}`}
                          mode="tags"
                          open={false}
                          placeholder={t('label.add-entity', {
                            entity: t('label.task-form-options'),
                          })}
                          tokenSeparators={[',']}
                          value={field.options}
                          onChange={(value) =>
                            updateField(index, (currentField) => ({
                              ...currentField,
                              options: value,
                            }))
                          }
                        />
                      </Form.Item>
                    ) : null}

                    <Form.Item
                      className="m-b-0"
                      label={t('label.task-form-help-text')}>
                      <Input.TextArea
                        autoSize={{ minRows: 2, maxRows: 4 }}
                        data-testid={`${testIdPrefix}-field-description-${index}`}
                        placeholder={t('message.task-form-help-placeholder')}
                        value={field.description}
                        onChange={(event) =>
                          updateField(index, (currentField) => ({
                            ...currentField,
                            description: event.target.value,
                          }))
                        }
                      />
                    </Form.Item>

                    <Space size="large">
                      <Checkbox
                        checked={field.required}
                        data-testid={`${testIdPrefix}-field-required-${index}`}
                        onChange={(event) =>
                          updateField(index, (currentField) => ({
                            ...currentField,
                            required: event.target.checked,
                          }))
                        }>
                        {t('label.required')}
                      </Checkbox>
                      <Checkbox
                        checked={field.hidden}
                        data-testid={`${testIdPrefix}-field-hidden-${index}`}
                        onChange={(event) =>
                          updateField(index, (currentField) => ({
                            ...currentField,
                            hidden: event.target.checked,
                          }))
                        }>
                        {t('label.task-form-hidden')}
                      </Checkbox>
                    </Space>
                  </div>
                </Card>
              );
            })
          ) : (
            <Empty
              data-testid={`${testIdPrefix}-empty`}
              description={t('message.task-form-no-fields')}
              image={Empty.PRESENTED_IMAGE_SIMPLE}
            />
          )}
        </div>

        <Card
          className="task-form-builder-section__preview"
          data-testid={`${testIdPrefix}-preview`}
          size="small"
          title={t('label.preview')}>
          {fields.length ? (
            <div>
              <TaskPayloadSchemaFields
                mode="read"
                payload={previewPayload}
                schema={previewConfig.formSchema}
                uiSchema={previewConfig.uiSchema}
              />
            </div>
          ) : (
            <Typography.Text className="text-grey-muted">
              {t('message.task-form-preview-placeholder')}
            </Typography.Text>
          )}
        </Card>
      </div>
    </div>
  );
};

export default TaskFormBuilderSection;
