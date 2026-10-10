/*
 * Copyright 2026 Collate.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import { Button } from 'antd';
import { isValidElement } from 'react';
import { useTranslation } from 'react-i18next';
import FormDrawer from './FormDrawer';
import { LegacyFormDrawerProps } from './LegacyFormDrawer.interface';

/** Compatibility for explicitly audited Ant Design form callers. */
export const LegacyFormDrawer = ({
  open,
  visible,
  title,
  onCancel,
  onOk,
  width,
  confirmLoading,
  isSubmitting,
  footer,
  children,
  okText,
  cancelText,
  okButtonProps,
  cancelButtonProps,
  okType = 'primary',
  className,
  bodyStyle,
  destroyOnClose,
  keyboard,
  maskClosable,
  closeIcon,
  getContainer,
  'data-testid': testId,
}: LegacyFormDrawerProps) => {
  const { t } = useTranslation();
  const pending = Boolean(
    isSubmitting || confirmLoading || okButtonProps?.loading
  );

  return (
    <FormDrawer
      bodyStyle={{
        ...bodyStyle,
        height: undefined,
        maxHeight: undefined,
        overflow: undefined,
        overflowY: undefined,
      }}
      className={className}
      closeButtonTestId={
        isValidElement<{ dataTestId?: string }>(closeIcon)
          ? closeIcon.props.dataTestId
          : undefined
      }
      data-testid={testId}
      destroyOnClose={destroyOnClose}
      footer={
        footer === undefined ? (
          <>
            <Button
              {...cancelButtonProps}
              disabled={pending || cancelButtonProps?.disabled}
              onClick={onCancel}>
              {cancelText ?? t('label.cancel')}
            </Button>
            <Button
              {...okButtonProps}
              danger={okType === 'danger' || okButtonProps?.danger}
              disabled={pending || okButtonProps?.disabled}
              loading={confirmLoading || okButtonProps?.loading}
              type={okType === 'danger' ? 'primary' : okType}
              onClick={onOk}>
              {okText ?? t('label.save')}
            </Button>
          </>
        ) : (
          footer
        )
      }
      getContainer={getContainer}
      isDismissable={maskClosable}
      isKeyboardDismissDisabled={keyboard === false}
      isOpen={open ?? visible ?? false}
      isSubmitting={pending}
      title={title}
      width={width}
      onClose={() => onCancel?.()}>
      {children}
    </FormDrawer>
  );
};

export default LegacyFormDrawer;
