/*
 *  Copyright 2025 Collate.
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
import {
  Box,
  Button,
  FeaturedIcon,
  Toggle,
  Typography,
} from '@openmetadata/ui-core-components';
import { CheckCircle, Lightbulb05 } from '@untitledui/icons';
import { FC, ReactNode } from 'react';
import { useTranslation } from 'react-i18next';

import FormDrawer from './FormDrawer';

type FeaturedIconColor = 'brand' | 'gray' | 'success' | 'warning' | 'error';

/** Width of the Form Hint column itself, per the approved design. */
const HINT_COLUMN_WIDTH = 380;
/**
 * Width of the form column. The approved design pairs a 1024px modal with a
 * 642px form, but the real Data Quality form needs the ~772px it had before the
 * hint moved inside the modal — at 642px its test-level cards wrap. The two
 * widths below are derived from this so the form column stays the same width
 * whether the hint is shown or hidden, and only the hint appears/disappears.
 */
const FORM_COLUMN_WIDTH = 772;

/** Modal width when the Form Hint column is shown. */
const WIDTH_WITH_HINT = FORM_COLUMN_WIDTH + HINT_COLUMN_WIDTH;
/** Modal width when the Form Hint column is collapsed. */
const WIDTH_WITHOUT_HINT = FORM_COLUMN_WIDTH;
/** Modal width for forms that have no Form Hint column at all. */
const WIDTH_NO_HINT_COLUMN = 820;

export interface AiFormModalProps {
  open: boolean;
  title: ReactNode;
  subtitle?: ReactNode;
  headerActions?: ReactNode;
  children: ReactNode;
  isSubmitting?: boolean;
  onClose: () => void;
  /**
   * Called when the submit button is pressed. Optional: forms that submit
   * natively via `submitFormId` do not need it.
   */
  onSubmit?: () => void | Promise<unknown>;
  /** Footer button test ids, defaulted to match the classic test case drawer. */
  submitTestId?: string;
  cancelTestId?: string;
  /** Footer submit button label, defaults to the create label. */
  submitLabel?: ReactNode;
  /**
   * Tri-state control for the Form Hint column.
   * - `undefined`: the form has no hint column; single-column layout.
   * - `true`: hint column shown; the modal widens.
   * - `false`: hint column collapsed; the modal narrows.
   *
   * The column itself is rendered by HookForm's `fieldDocDisplay="panel"` mode,
   * because it must live inside FieldDocProvider to read the focused field's
   * doc. This prop only drives the modal's width.
   */
  hintOpen?: boolean;
  /**
   * Toggles the hint column. Supplying it renders the Show Hint control in the
   * header; this modal owns that control because the hint column is what it is
   * for, and three callers had drifted into three copies of the same markup.
   *
   * The boolean stays with the caller rather than being internal state: the
   * form needs it too (`showFieldDocs`), and the form is a child the caller
   * renders, not something this modal owns.
   */
  onHintToggle?: (open: boolean) => void;
  /** Header featured-icon glyph. Defaults to CheckCircle. */
  icon?: FC<{ className?: string }>;
  /** Header featured-icon colour. Defaults to 'gray'. */
  iconColor?: FeaturedIconColor;
  /** Extra footer buttons, rendered between Cancel and the submit button. */
  footerActions?: ReactNode;
  /**
   * When set, the submit button becomes a native form submitter
   * (`form={submitFormId}` + `type="submit"`) instead of calling `onSubmit`.
   * For forms rendered as a `<HookForm id=...>` that submit themselves.
   */
  submitFormId?: string;
  /** Disables the submit button (e.g. while the form is loading). */
  isSubmitDisabled?: boolean;
}

export const AiFormModal: FC<AiFormModalProps> = ({
  open,
  title,
  subtitle,
  headerActions,
  children,
  isSubmitting,
  onClose,
  onSubmit,
  submitTestId = 'create-btn',
  cancelTestId = 'cancel-btn',
  hintOpen,
  onHintToggle,
  submitLabel,
  icon = CheckCircle,
  iconColor = 'gray',
  footerActions,
  submitFormId,
  isSubmitDisabled,
}) => {
  const { t } = useTranslation();
  const hasHintColumn = hintOpen !== undefined;
  const submitButtonProps = submitFormId
    ? { form: submitFormId, type: 'submit' as const }
    : { onClick: () => Promise.resolve(onSubmit?.()).catch(() => undefined) };

  return (
    <FormDrawer
      bodyClassName={hasHintColumn ? 'form-drawer__body--hint' : undefined}
      footer={
        <>
          <Button
            color="secondary"
            data-testid={cancelTestId}
            isDisabled={isSubmitting}
            onClick={onClose}>
            {t('label.cancel')}
          </Button>
          {footerActions}
          <Button
            color="primary"
            data-testid={submitTestId}
            isDisabled={isSubmitDisabled || isSubmitting}
            isLoading={isSubmitting}
            {...submitButtonProps}>
            {submitLabel ?? t('label.create')}
          </Button>
        </>
      }
      headerActions={
        <Box align="center" className="tw:shrink-0 tw:gap-4" direction="row">
          {headerActions}
          {onHintToggle && (
            <Box align="center" className="tw:gap-2" direction="row">
              <Lightbulb05 className="tw:size-4 tw:text-secondary" />
              <Typography
                className="tw:whitespace-nowrap tw:text-secondary"
                size="text-sm"
                weight="medium">
                {t('label.show-hint')}
              </Typography>
              <Toggle
                aria-label={t('label.show-hint')}
                data-testid="show-hint-toggle"
                isSelected={hintOpen}
                size="sm"
                onChange={onHintToggle}
              />
            </Box>
          )}
        </Box>
      }
      isOpen={open}
      isSubmitting={isSubmitting}
      subtitle={subtitle}
      title={title}
      titleLeading={
        <FeaturedIcon
          color={iconColor}
          icon={icon}
          radius="md"
          shape="square"
          size="md"
          theme="light"
        />
      }
      width={
        hasHintColumn
          ? hintOpen
            ? WIDTH_WITH_HINT
            : WIDTH_WITHOUT_HINT
          : WIDTH_NO_HINT_COLUMN
      }
      onClose={onClose}>
      {children}
    </FormDrawer>
  );
};
