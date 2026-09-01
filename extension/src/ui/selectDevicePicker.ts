// extension/src/ui/devicePicker.ts
import * as vscode from 'vscode';
import { AdbDevice } from '../adb/AdbDevice';
import { t } from '../i18n/strings';

interface DevicePickItem extends vscode.QuickPickItem {
  __device: AdbDevice;
}

export async function pickDevice(devices: AdbDevice[]): Promise<AdbDevice | undefined> {
  const items: DevicePickItem[] = devices.map((d) => ({
    label: d.serial,
    picked: false,
    __device: d,
  }));
  const pick = await vscode.window.showQuickPick<DevicePickItem>(items, {
    title: t.pickDeviceTitle(),
    placeHolder: t.pickDevicePlaceholder(),
    matchOnDescription: false,
  } as vscode.QuickPickOptions);
  return pick?.__device;
}
