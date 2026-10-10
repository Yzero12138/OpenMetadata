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
import type { Dialog } from '@openmetadata/ui-core-components';
import { ComponentProps, ReactNode } from 'react';

export interface FormDrawerSectionProps {
  children?: ReactNode;
  title?: string;
  className?: string;
}

export interface CoreFormDrawerProps
  extends Omit<ComponentProps<typeof Dialog>, 'children'> {
  isOpen: boolean;
  onOpenChange?: (open: boolean) => void;
  isSubmitting?: boolean;
  isDismissable?: boolean;
  destroyOnClose?: boolean;
  children?: ReactNode;
  'data-testid'?: string;
}
