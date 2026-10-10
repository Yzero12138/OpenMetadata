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
import { CloseButton, SlideoutMenu } from '@openmetadata/ui-core-components';
import { ConfigProvider } from 'antd';
import classNames from 'classnames';
import { useCallback, useEffect, useRef, useState } from 'react';
import { UNSAFE_PortalProvider as PortalProvider } from 'react-aria';
import { Heading } from 'react-aria-components';
import { createPortal } from 'react-dom';
import { useTranslation } from 'react-i18next';
import './form-drawer.less';
import { FormDrawerProps } from './FormDrawer.interface';
import { FormDrawerFooterContext } from './FormDrawerActions';

export const FormDrawer = ({
  isOpen,
  title,
  onClose,
  width = 640,
  isSubmitting = false,
  footer,
  children,
  className,
  'data-testid': testId,
  subtitle,
  headerActions,
  titleLeading,
  headerContent,
  bodyClassName,
  bodyStyle,
  destroyOnClose = false,
  isDismissable = false,
  isKeyboardDismissDisabled = false,
  closeButtonTestId,
  getContainer,
}: FormDrawerProps) => {
  const { t } = useTranslation();
  const parkingRef = useRef<HTMLDivElement>(null);
  const popupRef = useRef<HTMLDivElement>(null);
  const [hasOpened, setHasOpened] = useState(isOpen);
  const [contentHost] = useState(() => document.createElement('div'));
  const [footerHost] = useState(() => {
    const host = document.createElement('div');
    host.className = 'form-drawer__action-slot';

    return host;
  });

  useEffect(() => {
    if (isOpen) {
      setHasOpened(true);
    }
  }, [isOpen]);

  const attachBody = useCallback(
    (node: HTMLDivElement | null) => {
      (node ?? parkingRef.current)?.appendChild(contentHost);
    },
    [contentHost]
  );
  const getPopupContainer = useCallback(
    () => popupRef.current ?? document.body,
    []
  );
  const attachFooter = useCallback(
    (node: HTMLFieldSetElement | null) => {
      node?.appendChild(footerHost);
    },
    [footerHost]
  );
  const handleClose = useCallback(() => {
    if (!isSubmitting) {
      onClose();
    }
  }, [isSubmitting, onClose]);

  return (
    <PortalProvider getContainer={getContainer}>
      <div hidden aria-hidden="true" ref={parkingRef} />
      <SlideoutMenu
        className="form-drawer__overlay"
        data-form-drawer-overlay="true"
        data-testid={testId}
        dialogClassName={classNames('form-drawer__dialog', className)}
        isDismissable={isDismissable && !isSubmitting}
        isKeyboardDismissDisabled={isSubmitting || isKeyboardDismissDisabled}
        isOpen={isOpen}
        style={{ zIndex: 1000 }}
        width={width}
        onOpenChange={(open) => !open && handleClose()}>
        <header className="form-drawer__header tw:border-b tw:border-secondary tw:bg-primary">
          {titleLeading}
          <div className="tw:min-w-0 tw:flex-1">
            <Heading
              aria-label={
                headerContent && typeof title === 'string' ? title : undefined
              }
              className="tw:m-0 tw:text-lg tw:font-semibold tw:text-primary"
              level={2}
              slot="title">
              {headerContent ?? title}
            </Heading>
            {subtitle && (
              <div className="tw:mt-1 tw:text-sm tw:text-tertiary">
                {subtitle}
              </div>
            )}
          </div>
          {headerActions}
          <CloseButton
            data-testid={closeButtonTestId}
            isDisabled={isSubmitting}
            label={t('label.close')}
            size="md"
            onPress={handleClose}
          />
        </header>
        <SlideoutMenu.Content
          className={classNames('form-drawer__body', bodyClassName)}
          data-testid="form-drawer-body"
          role="presentation"
          style={bodyStyle}>
          <div className="form-drawer__body-host" ref={attachBody} />
        </SlideoutMenu.Content>
        <SlideoutMenu.Footer
          className="form-drawer__footer tw:bg-primary"
          data-empty-footer={footer === undefined || footer === null}
          data-testid="form-drawer-footer">
          <fieldset
            aria-busy={isSubmitting}
            className="form-drawer__actions"
            disabled={isSubmitting}
            ref={attachFooter}
            onClickCapture={(event) => {
              if (isSubmitting) {
                event.preventDefault();
                event.stopPropagation();
              }
            }}>
            {footer}
          </fieldset>
        </SlideoutMenu.Footer>
        <div className="form-drawer__popups" ref={popupRef} />
      </SlideoutMenu>
      {(isOpen || (hasOpened && !destroyOnClose)) &&
        createPortal(
          <PortalProvider getContainer={getPopupContainer}>
            <ConfigProvider getPopupContainer={getPopupContainer}>
              <FormDrawerFooterContext.Provider value={footerHost}>
                <div
                  className="form-drawer__content"
                  onSubmitCapture={(event) => {
                    if (isSubmitting) {
                      event.preventDefault();
                      event.stopPropagation();
                    }
                  }}>
                  {children}
                </div>
              </FormDrawerFooterContext.Provider>
            </ConfigProvider>
          </PortalProvider>,
          contentHost
        )}
    </PortalProvider>
  );
};

export default FormDrawer;
