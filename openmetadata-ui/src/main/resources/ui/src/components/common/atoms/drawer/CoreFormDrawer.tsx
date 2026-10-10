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
import { Children, cloneElement, isValidElement, ReactNode } from 'react';
import {
  CoreFormDrawerProps,
  FormDrawerSectionProps as SectionProps,
} from './CoreFormDrawer.interface';
import FormDrawer from './FormDrawer';

const Header = (_props: SectionProps) => null;
const Footer = (_props: SectionProps) => null;
const Content = ({ children, className }: SectionProps) => (
  <div className={`tw:flex tw:flex-col tw:gap-4 ${className ?? ''}`}>
    {children}
  </div>
);

const getText = (node: ReactNode): string =>
  Children.toArray(node)
    .map((child) => {
      if (typeof child === 'string' || typeof child === 'number') {
        return String(child);
      }

      return isValidElement<SectionProps>(child)
        ? getText(child.props.children)
        : '';
    })
    .join(' ');

const normalizeHeadings = (node: ReactNode): ReactNode =>
  Children.map(node, (child) => {
    if (!isValidElement<{ as?: string; children?: ReactNode }>(child)) {
      return child;
    }

    return cloneElement(child, {
      ...(child.props.as && /^h[1-6]$/.test(child.props.as)
        ? { as: 'span' }
        : {}),
      ...(child.props.children !== undefined
        ? { children: normalizeHeadings(child.props.children) }
        : {}),
    });
  });

const CoreFormDrawerBase = ({
  isOpen,
  onOpenChange,
  isSubmitting,
  isDismissable,
  destroyOnClose,
  onClose,
  title,
  width,
  className,
  children,
  'aria-label': ariaLabel,
  'data-testid': testId,
}: CoreFormDrawerProps) => {
  const sections = Children.toArray(children);
  const header = sections.find(
    (node) => isValidElement(node) && node.type === Header
  );
  const footer = sections.find(
    (node) => isValidElement(node) && node.type === Footer
  );
  const headerProps = isValidElement<SectionProps>(header)
    ? header.props
    : undefined;
  const footerProps = isValidElement<SectionProps>(footer)
    ? footer.props
    : undefined;
  const accessibleTitle =
    title || headerProps?.title || getText(headerProps?.children) || ariaLabel;

  return (
    <FormDrawer
      className={typeof className === 'string' ? className : undefined}
      data-testid={testId}
      destroyOnClose={destroyOnClose}
      footer={footerProps?.children}
      headerContent={
        headerProps && (
          <>
            {headerProps.title && (
              <div className="tw:text-lg tw:font-semibold tw:text-primary">
                {headerProps.title}
              </div>
            )}
            {normalizeHeadings(headerProps.children)}
          </>
        )
      }
      isDismissable={isDismissable}
      isOpen={isOpen}
      isSubmitting={isSubmitting}
      title={accessibleTitle}
      width={width}
      onClose={() => {
        onClose?.();
        if (!onClose) {
          onOpenChange?.(false);
        }
      }}>
      {sections.filter(
        (node) =>
          !isValidElement(node) ||
          (node.type !== Header && node.type !== Footer)
      )}
    </FormDrawer>
  );
};

export const CoreFormDrawer = Object.assign(CoreFormDrawerBase, {
  Header,
  Content,
  Footer,
});

export default CoreFormDrawer;
