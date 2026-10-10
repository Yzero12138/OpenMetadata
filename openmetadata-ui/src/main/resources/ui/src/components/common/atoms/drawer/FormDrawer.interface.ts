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
import { CSSProperties, ReactNode } from 'react';
import { PortalProviderProps } from 'react-aria';

export interface FormDrawerProps {
  isOpen: boolean;
  title: ReactNode;
  onClose: () => void;
  width?: number | string;
  isSubmitting?: boolean;
  footer?: ReactNode;
  children: ReactNode;
  className?: string;
  'data-testid'?: string;
  subtitle?: ReactNode;
  headerActions?: ReactNode;
  titleLeading?: ReactNode;
  headerContent?: ReactNode;
  bodyClassName?: string;
  bodyStyle?: CSSProperties;
  destroyOnClose?: boolean;
  isDismissable?: boolean;
  isKeyboardDismissDisabled?: boolean;
  closeButtonTestId?: string;
  getContainer?: PortalProviderProps['getContainer'];
}

export interface FormDrawerActionsProps {
  children: ReactNode;
}
