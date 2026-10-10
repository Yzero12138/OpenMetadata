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
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import { useRef, useState } from 'react';
import FormDrawer from '../../common/atoms/drawer/FormDrawer';
import LinkModal from './LinkModal';

const onSave = jest.fn();
const onCancel = jest.fn();

const defaultProps = {
  isOpen: true,
  data: { href: '' },
  onSave,
  onCancel,
};

describe('LinkModal', () => {
  afterEach(() => {
    jest.clearAllMocks();
  });

  it('should render the "Add link" title and the link input when href is empty', () => {
    render(<LinkModal {...defaultProps} />);

    expect(
      screen.getByRole('heading', { name: 'label.add-entity' })
    ).toBeInTheDocument();
    expect(screen.getByRole('textbox')).toBeInTheDocument();
  });

  it('should render the "Edit link" title when an href is provided', () => {
    render(<LinkModal {...defaultProps} data={{ href: 'https://x.com' }} />);

    expect(
      screen.getByRole('heading', { name: 'label.edit-entity' })
    ).toBeInTheDocument();
  });

  it('should call onSave with the entered href on submit', async () => {
    render(<LinkModal {...defaultProps} />);

    fireEvent.change(screen.getByRole('textbox'), {
      target: { value: '{{buildEntityUrl event.entityType entity}}' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'label.save' }));

    await waitFor(() =>
      expect(onSave).toHaveBeenCalledWith({
        href: '{{buildEntityUrl event.entityType entity}}',
      })
    );

    expect(onSave).toHaveBeenCalledTimes(1);
  });

  it('should call onCancel when the cancel button is clicked', () => {
    render(<LinkModal {...defaultProps} />);

    fireEvent.click(screen.getByRole('button', { name: 'label.cancel' }));

    expect(onCancel).toHaveBeenCalledTimes(1);
  });

  it('should mount the modal inside the container returned by getContainer', () => {
    const host = document.createElement('div');
    host.setAttribute('data-testid', 'dialog-host');
    document.body.appendChild(host);

    const { unmount } = render(
      <LinkModal {...defaultProps} getContainer={() => host} />
    );

    expect(host.querySelector('.block-editor-link-modal')).toBeInTheDocument();

    unmount();
    host.remove();
  });

  it('edits and submits a link inside its parent editor dialog, then restores focus', async () => {
    const EditorDialog = () => {
      const [open, setOpen] = useState(false);
      const editorRef = useRef<HTMLDivElement>(null);

      const getContainer = () => {
        const dialog =
          editorRef.current?.closest<HTMLElement>('[role="dialog"]');
        if (!dialog) {
          throw new Error('The editor dialog must be mounted');
        }

        return dialog;
      };

      return (
        <FormDrawer isOpen title="Editor dialog" onClose={jest.fn()}>
          <div ref={editorRef}>
            <button onClick={() => setOpen(true)}>Insert link</button>
            {open && (
              <LinkModal
                {...defaultProps}
                getContainer={getContainer}
                isOpen={open}
                onCancel={() => setOpen(false)}
                onSave={(data) => {
                  onSave(data);
                  setOpen(false);
                }}
              />
            )}
          </div>
        </FormDrawer>
      );
    };
    render(<EditorDialog />);
    const editorDialog = screen.getByRole('dialog', { name: 'Editor dialog' });
    const launcher = screen.getByRole('button', { name: 'Insert link' });
    launcher.focus();
    fireEvent.click(launcher);
    await act(async () => jest.runOnlyPendingTimers());

    const linkDialog = screen.getByRole('dialog', { name: 'label.add-entity' });

    expect(
      linkDialog.closest('[data-form-drawer-overlay]')?.parentElement
    ).toBe(editorDialog);

    const input = within(linkDialog).getByRole('textbox');

    expect(input).toHaveFocus();

    fireEvent.change(input, {
      target: { value: 'https://hospital.example/catalog' },
    });
    fireEvent.click(
      within(linkDialog).getByRole('button', { name: 'label.save' })
    );

    await waitFor(() =>
      expect(onSave).toHaveBeenCalledWith({
        href: 'https://hospital.example/catalog',
      })
    );

    expect(onSave).toHaveBeenCalledTimes(1);

    await act(async () => jest.runOnlyPendingTimers());
    await waitFor(() => expect(launcher).toHaveFocus());

    expect(
      screen.getByRole('dialog', { name: 'Editor dialog' })
    ).toBeInTheDocument();
    expect(
      screen.queryByRole('dialog', { name: 'label.add-entity' })
    ).not.toBeInTheDocument();
  });
});
