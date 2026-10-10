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
import { Select } from '@openmetadata/ui-core-components';
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { FormEvent, useState } from 'react';
import { UNSAFE_PortalProvider as PortalProvider } from 'react-aria';
import RetentionPeriod from '../../../Database/RetentionPeriod/RetentionPeriod.component';
import EntityNameModal from '../../../Modals/EntityNameModal/EntityNameModal.component';
import { AiFormModal } from './AiFormModal';
import FormDrawer from './FormDrawer';
import { FormDrawerActions } from './FormDrawerActions';

describe('right-side form drawers', () => {
  it('inherits the existing native portal host when no container is specified', () => {
    const host = document.createElement('div');
    document.body.appendChild(host);
    const { unmount } = render(
      <PortalProvider getContainer={() => host}>
        <FormDrawer isOpen title="Inherited host" onClose={jest.fn()}>
          <input aria-label="Hosted draft" />
        </FormDrawer>
      </PortalProvider>
    );

    expect(host).toContainElement(
      screen.getByRole('dialog', { name: 'Inherited host' })
    );

    unmount();
    host.remove();
  });

  it('uses the right-side body with a fixed header and native footer submitter', async () => {
    const onSubmit = jest.fn((event: FormEvent<HTMLFormElement>) =>
      event.preventDefault()
    );
    render(
      <AiFormModal
        open
        submitFormId="quality-form"
        title="Quality rule"
        onClose={jest.fn()}>
        <form id="quality-form" onSubmit={onSubmit}>
          <input aria-label="Rule name" />
        </form>
      </AiFormModal>
    );

    expect(screen.getByRole('dialog', { name: 'Quality rule' })).toHaveClass(
      'form-drawer__dialog'
    );
    expect(screen.getByTestId('form-drawer-body')).toContainElement(
      screen.getByRole('textbox')
    );
    expect(screen.getByTestId('form-drawer-footer')).not.toContainElement(
      screen.getByRole('textbox')
    );

    fireEvent.click(screen.getByTestId('create-btn'));
    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
  });

  it('blocks cancel, the close button, and Escape while a save is pending', () => {
    const onClose = jest.fn();
    render(
      <AiFormModal isSubmitting open title="Pending save" onClose={onClose}>
        <input aria-label="Draft" />
      </AiFormModal>
    );

    fireEvent.click(screen.getByTestId('cancel-btn'));
    fireEvent.keyDown(screen.getByRole('dialog'), {
      key: 'Escape',
      code: 'Escape',
    });

    expect(screen.getByRole('button', { name: 'label.close' })).toBeDisabled();
    expect(onClose).not.toHaveBeenCalled();
  });

  it('returns focus to the launcher and retains the draft after dismissal', async () => {
    const Draft = () => {
      const [value, setValue] = useState('');

      return (
        <input
          aria-label="Draft"
          value={value}
          onChange={(event) => setValue(event.target.value)}
        />
      );
    };
    const Example = () => {
      const [open, setOpen] = useState(false);

      return (
        <>
          <button onClick={() => setOpen(true)}>Open form</button>
          <AiFormModal
            open={open}
            title="Draft form"
            onClose={() => setOpen(false)}>
            <Draft />
          </AiFormModal>
        </>
      );
    };
    render(<Example />);
    const launcher = screen.getByRole('button', { name: 'Open form' });
    launcher.focus();
    fireEvent.click(launcher);
    await act(async () => jest.runOnlyPendingTimers());
    fireEvent.change(screen.getByRole('textbox', { name: 'Draft' }), {
      target: { value: 'Unfinished rule' },
    });
    fireEvent.keyDown(screen.getByRole('dialog'), {
      key: 'Escape',
      code: 'Escape',
    });
    await act(async () => jest.runOnlyPendingTimers());
    await waitFor(() => expect(launcher).toHaveFocus());
    fireEvent.click(launcher);

    expect(screen.getByRole('textbox', { name: 'Draft' })).toHaveValue(
      'Unfinished rule'
    );
  });

  it('keeps the rename draft through parent rerenders and disables dismissal during save', async () => {
    let finishSave: (() => void) | undefined;
    const onSave = jest.fn(
      () =>
        new Promise<void>((resolve) => {
          finishSave = resolve;
        })
    );
    const onCancel = jest.fn();
    const props = {
      visible: true,
      allowRename: true,
      title: 'Rename table',
      onSave,
      onCancel,
    };
    const { rerender } = render(
      <EntityNameModal
        {...props}
        entity={{ name: 'table', displayName: 'Before' }}
      />
    );
    fireEvent.change(screen.getByTestId('displayName'), {
      target: { value: 'Draft name' },
    });
    rerender(
      <EntityNameModal
        {...props}
        entity={{ name: 'table', displayName: 'Before' }}
      />
    );

    expect(screen.getByTestId('displayName')).toHaveValue('Draft name');
    expect(screen.getByRole('dialog', { name: 'Rename table' })).toHaveClass(
      'form-drawer__dialog'
    );

    fireEvent.click(screen.getByTestId('save-button'));
    await waitFor(() =>
      expect(onSave).toHaveBeenCalledWith({
        name: 'table',
        displayName: 'Draft name',
      })
    );
    fireEvent.click(screen.getByRole('button', { name: 'label.cancel' }));

    expect(onCancel).not.toHaveBeenCalled();

    await act(async () => finishSave?.());
  });

  it('submits the existing retention form once from the drawer footer', async () => {
    const onUpdate = jest.fn().mockResolvedValue(undefined);
    render(
      <RetentionPeriod
        hasPermission
        retentionPeriod="P30D"
        onUpdate={onUpdate}
      />
    );
    fireEvent.click(screen.getByTestId('edit-retention-period-button'));

    expect(screen.getByRole('dialog')).toHaveClass('form-drawer__dialog');

    fireEvent.change(screen.getByTestId('retention-period-input'), {
      target: { value: 'P60D' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'label.save' }));
    await waitFor(() => expect(onUpdate).toHaveBeenCalledWith('P60D'));

    expect(onUpdate).toHaveBeenCalledTimes(1);
  });

  it('keeps native select portals inside the drawer without dismissing the form', async () => {
    const onClose = jest.fn();
    render(
      <FormDrawer isOpen title="Export settings" onClose={onClose}>
        <Select defaultSelectedKey="csv" label="Format">
          <Select.Item id="csv" label="CSV" />
          <Select.Item id="json" label="JSON" />
        </Select>
      </FormDrawer>
    );
    fireEvent.click(screen.getByRole('button', { name: /Format/ }));
    const option = await screen.findByRole('option', { name: 'JSON' });

    expect(option.closest('.form-drawer__popups')).toBeInTheDocument();

    fireEvent.click(option);

    expect(screen.getByRole('button', { name: /Format/ })).toHaveTextContent(
      'JSON'
    );
    expect(onClose).not.toHaveBeenCalled();
  });

  it('moves nested actions to the footer and blocks repeated form submission while pending', () => {
    const onSubmit = jest.fn((event: FormEvent<HTMLFormElement>) =>
      event.preventDefault()
    );
    const body = (
      <form id="nested-form" onSubmit={onSubmit}>
        <input aria-label="Nested draft" />
        <FormDrawerActions>
          <button form="nested-form" type="submit">
            Save nested form
          </button>
        </FormDrawerActions>
      </form>
    );
    const { rerender } = render(
      <FormDrawer isOpen title="Nested form" onClose={jest.fn()}>
        {body}
      </FormDrawer>
    );
    const submit = screen.getByRole('button', { name: 'Save nested form' });

    expect(screen.getByTestId('form-drawer-footer')).toContainElement(submit);
    expect(screen.getByTestId('form-drawer-body')).not.toContainElement(submit);

    fireEvent.click(submit);

    expect(onSubmit).toHaveBeenCalledTimes(1);

    rerender(
      <FormDrawer isOpen isSubmitting title="Nested form" onClose={jest.fn()}>
        {body}
      </FormDrawer>
    );
    fireEvent.submit(document.getElementById('nested-form')!);

    expect(onSubmit).toHaveBeenCalledTimes(1);
  });
});
