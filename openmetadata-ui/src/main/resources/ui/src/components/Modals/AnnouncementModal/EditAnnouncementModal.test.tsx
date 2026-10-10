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
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { Form } from 'antd';
import { DateTime } from 'luxon';
import * as ToastUtils from '../../../utils/ToastUtils';
import EditAnnouncementModal from './EditAnnouncementModal';

// Mock dependencies
jest.mock('../../../utils/ToastUtils', () => ({
  showErrorToast: jest.fn(),
}));

jest.mock('react-i18next', () => ({
  ...jest.requireActual('react-i18next'),
  useTranslation: () => ({ t: (key: string) => key }),
}));

jest.mock('../../../utils/date-time/DateTimeUtils', () => ({
  ...jest.requireActual('../../../utils/date-time/DateTimeUtils'),
  getTimeZone: () => 'UTC',
}));

jest.mock('../../../utils/formUtils', () => ({
  getField: jest.fn(() => (
    <Form.Item name="description">
      <textarea data-testid="mocked-description-field" />
    </Form.Item>
  )),
}));

const mockShowErrorToast = ToastUtils.showErrorToast as jest.MockedFunction<
  typeof ToastUtils.showErrorToast
>;

const mockAnnouncement = {
  description: 'Test announcement description',
  startTime: DateTime.now().plus({ hours: 1 }).toMillis(),
  endTime: DateTime.now().plus({ hours: 3 }).toMillis(),
};

const defaultProps = {
  open: true,
  announcementTitle: 'Test Announcement Title',
  announcement: mockAnnouncement,
  onCancel: jest.fn(),
  onConfirm: jest.fn(),
};

describe('EditAnnouncementModal', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('should render the modal with pre-filled data when open', () => {
    render(<EditAnnouncementModal {...defaultProps} />);

    expect(screen.getByText('label.edit-an-announcement')).toBeInTheDocument();
    expect(
      screen.getByDisplayValue('Test Announcement Title')
    ).toBeInTheDocument();
    expect(screen.getByLabelText('label.title:')).toBeInTheDocument();
    expect(screen.getByTestId('mocked-description-field')).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: 'label.save' })
    ).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: 'label.cancel' })
    ).toBeInTheDocument();
  });

  it('should not render the modal when closed', () => {
    render(<EditAnnouncementModal {...defaultProps} open={false} />);

    expect(
      screen.queryByText('label.edit-an-announcement')
    ).not.toBeInTheDocument();
  });

  it('should show error when start time is greater than or equal to end time', async () => {
    render(
      <EditAnnouncementModal
        {...defaultProps}
        announcement={{
          ...mockAnnouncement,
          startTime: mockAnnouncement.endTime,
        }}
      />
    );

    fireEvent.click(screen.getByRole('button', { name: 'label.save' }));

    await waitFor(() =>
      expect(mockShowErrorToast).toHaveBeenCalledWith(
        'message.announcement-invalid-start-time'
      )
    );

    expect(defaultProps.onConfirm).not.toHaveBeenCalled();
  });

  it('should successfully update announcement with valid data', async () => {
    const onConfirmMock = jest.fn();

    render(
      <EditAnnouncementModal {...defaultProps} onConfirm={onConfirmMock} />
    );

    fireEvent.change(screen.getByLabelText('label.title:'), {
      target: { value: 'Updated Announcement Title' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'label.save' }));

    await waitFor(() =>
      expect(onConfirmMock).toHaveBeenCalledWith(
        'Updated Announcement Title',
        mockAnnouncement
      )
    );
  });

  it('should call onCancel when cancel button is clicked', async () => {
    const onCancelMock = jest.fn();

    render(<EditAnnouncementModal {...defaultProps} onCancel={onCancelMock} />);

    const cancelButton = screen.getByRole('button', { name: 'label.cancel' });
    await act(async () => {
      fireEvent.click(cancelButton);
    });

    expect(onCancelMock).toHaveBeenCalledTimes(1);
  });

  it('awaits announcement save and blocks repeat save, cancel, close and Escape', async () => {
    let finishSave: (() => void) | undefined;
    const onConfirm = jest.fn(
      () =>
        new Promise<void>((resolve) => {
          finishSave = resolve;
        })
    );
    const onCancel = jest.fn();
    render(
      <EditAnnouncementModal
        {...defaultProps}
        onCancel={onCancel}
        onConfirm={onConfirm}
      />
    );

    fireEvent.click(screen.getByRole('button', { name: 'label.save' }));
    await waitFor(() => expect(onConfirm).toHaveBeenCalledTimes(1));

    expect(screen.getByRole('button', { name: /label.save$/ })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'label.cancel' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'label.close' })).toBeDisabled();

    fireEvent.click(screen.getByRole('button', { name: /label.save$/ }));
    fireEvent.submit(screen.getByTestId('announcement-form'));
    fireEvent.click(screen.getByRole('button', { name: 'label.cancel' }));
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' });

    expect(onConfirm).toHaveBeenCalledTimes(1);
    expect(onCancel).not.toHaveBeenCalled();

    await act(async () => finishSave?.());

    expect(
      screen.getByRole('button', { name: 'label.close' })
    ).not.toBeDisabled();

    fireEvent.click(screen.getByRole('button', { name: 'label.close' }));

    expect(onCancel).toHaveBeenCalledTimes(1);
  });

  it('retains the announcement draft and restores save after rejection', async () => {
    let failSave: ((error: Error) => void) | undefined;
    const saveError = new Error('Announcement save failed');
    const onConfirm = jest.fn().mockImplementationOnce(
      () =>
        new Promise<void>((_resolve, reject) => {
          failSave = reject;
        })
    );
    render(<EditAnnouncementModal {...defaultProps} onConfirm={onConfirm} />);
    fireEvent.change(screen.getByLabelText('label.title:'), {
      target: { value: 'Unfinished announcement' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'label.save' }));
    await waitFor(() => expect(onConfirm).toHaveBeenCalledTimes(1));

    await act(async () => failSave?.(saveError));

    expect(mockShowErrorToast).toHaveBeenCalledWith(saveError);
    expect(screen.getByLabelText('label.title:')).toHaveValue(
      'Unfinished announcement'
    );
    expect(
      screen.getByRole('button', { name: 'label.save' })
    ).not.toBeDisabled();

    fireEvent.click(screen.getByRole('button', { name: 'label.save' }));
    await waitFor(() => expect(onConfirm).toHaveBeenCalledTimes(2));
  });
});
