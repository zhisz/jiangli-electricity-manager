package com.shangzhili.electricityreminder;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 首页添加房间悬浮窗。
 *
 * <p>目录读取放在独立单线程中，主线程只更新选择状态。用户取消窗口后，迟到的网络结果
 * 会被 {@link #closed} 拦截，不会继续打开子窗口或持有 Activity。最终房间码只使用
 * queryRoom 返回值，不根据名称或楼层自行拼接。</p>
 */
public final class AddRoomDialog {
    public interface OnRoomAddedListener {
        void onRoomAdded(String roomId);
    }

    private final Activity activity;
    private final RoomRepository repository;
    private final OnRoomAddedListener listener;
    private final ElectricityDirectoryClient directoryClient = new ElectricityDirectoryClient();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private AlertDialog dialog;
    private Button buildingButton;
    private Button floorButton;
    private Button roomButton;
    private EditText aliasInput;
    private TextView statusText;
    private List<DirectoryOption> buildings = Collections.emptyList();
    private List<DirectoryOption> floors = Collections.emptyList();
    private List<DirectoryOption> rooms = Collections.emptyList();
    private DirectoryOption selectedBuilding;
    private DirectoryOption selectedFloor;
    private DirectoryOption selectedRoom;
    private String previousDefaultAlias = "";
    private boolean closed;

    public AddRoomDialog(
            Activity activity,
            RoomRepository repository,
            OnRoomAddedListener listener
    ) {
        this.activity = activity;
        this.repository = repository;
        this.listener = listener;
    }

    public void show() {
        View content = activity.getLayoutInflater().inflate(
                R.layout.dialog_add_room, null, false
        );
        buildingButton = content.findViewById(R.id.addRoomBuildingButton);
        floorButton = content.findViewById(R.id.addRoomFloorButton);
        roomButton = content.findViewById(R.id.addRoomRoomButton);
        aliasInput = content.findViewById(R.id.addRoomAliasInput);
        statusText = content.findViewById(R.id.addRoomStatusText);

        dialog = new AlertDialog.Builder(activity)
                .setTitle("添加房间")
                .setView(content)
                .setNegativeButton("取消", null)
                // 先创建占位监听器；show() 后覆盖，才能在校验失败时保持窗口不关闭。
                .setPositiveButton("添加", null)
                .create();
        dialog.setOnDismissListener(ignored -> close());
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(view -> saveSelectedRoom());
        });
        buildingButton.setOnClickListener(view -> {
            if (buildings.isEmpty()) loadBuildings();
            else showOptions("选择楼栋", buildings, this::selectBuilding);
        });
        floorButton.setOnClickListener(view -> {
            if (floors.isEmpty() && selectedBuilding != null) selectBuilding(selectedBuilding);
            else showOptions("选择楼层", floors, this::selectFloor);
        });
        roomButton.setOnClickListener(view -> {
            if (rooms.isEmpty() && selectedFloor != null) selectFloor(selectedFloor);
            else showOptions("选择房间", rooms, this::selectRoom);
        });
        dialog.show();
        loadBuildings();
    }

    private void loadBuildings() {
        setBusy("正在读取南昌校区楼栋……");
        executor.execute(() -> {
            try {
                List<DirectoryOption> result = directoryClient.queryBuildings();
                runOnUiThread(() -> {
                    buildings = result;
                    buildingButton.setEnabled(true);
                    buildingButton.setText("选择楼栋");
                    setStatus("楼栋目录已加载，请依次完成选择", false);
                });
            } catch (Exception exception) {
                runOnUiThread(() -> {
                    buildingButton.setEnabled(true);
                    buildingButton.setText("重试加载楼栋");
                    showFailure("楼栋加载失败", exception);
                });
            }
        });
    }

    private void selectBuilding(DirectoryOption building) {
        selectedBuilding = building;
        selectedFloor = null;
        selectedRoom = null;
        buildingButton.setText("楼栋：" + building.name);
        floorButton.setText("正在加载楼层……");
        floorButton.setEnabled(false);
        roomButton.setText("选择房间");
        roomButton.setEnabled(false);
        updateAddEnabled();
        setBusy("正在读取“" + building.name + "”的楼层……");
        executor.execute(() -> {
            try {
                List<DirectoryOption> result = directoryClient.queryFloors(building.code);
                runOnUiThread(() -> {
                    // 用户快速改选楼栋时，旧请求结果不得覆盖当前选择。
                    if (selectedBuilding != building) return;
                    floors = result;
                    floorButton.setText("选择楼层");
                    floorButton.setEnabled(true);
                    setStatus("请选择楼层", false);
                });
            } catch (Exception exception) {
                runOnUiThread(() -> {
                    if (selectedBuilding == building) {
                        floors = Collections.emptyList();
                        floorButton.setEnabled(true);
                        floorButton.setText("重试加载楼层");
                    }
                    showFailure("楼层加载失败", exception);
                });
            }
        });
    }

    private void selectFloor(DirectoryOption floor) {
        selectedFloor = floor;
        selectedRoom = null;
        floorButton.setText("楼层：" + floor.name);
        roomButton.setText("正在加载房间……");
        roomButton.setEnabled(false);
        updateAddEnabled();
        setBusy("正在读取“" + floor.name + "”的房间……");
        DirectoryOption building = selectedBuilding;
        executor.execute(() -> {
            try {
                List<DirectoryOption> result = directoryClient.queryRooms(
                        building.code, floor.code
                );
                runOnUiThread(() -> {
                    if (selectedBuilding != building || selectedFloor != floor) return;
                    rooms = result;
                    roomButton.setText("选择房间");
                    roomButton.setEnabled(true);
                    setStatus("请选择房间", false);
                });
            } catch (Exception exception) {
                runOnUiThread(() -> {
                    if (selectedBuilding == building && selectedFloor == floor) {
                        rooms = Collections.emptyList();
                        roomButton.setEnabled(true);
                        roomButton.setText("重试加载房间");
                    }
                    showFailure("房间加载失败", exception);
                });
            }
        });
    }

    private void selectRoom(DirectoryOption room) {
        selectedRoom = room;
        roomButton.setText("房间：" + room.name);
        String currentAlias = aliasInput.getText().toString().trim();
        // 只有空值或上一次自动填入值才会随重新选择更新；用户自己写的备注不会被覆盖。
        if (currentAlias.isEmpty() || currentAlias.equals(previousDefaultAlias)) {
            aliasInput.setText(room.name);
            aliasInput.setSelection(aliasInput.length());
        }
        previousDefaultAlias = room.name;
        setStatus(
                selectedBuilding.name + " · " + selectedFloor.name + " · " + room.name,
                false
        );
        updateAddEnabled();
    }

    private void saveSelectedRoom() {
        if (selectedRoom == null) {
            setStatus("请先选择完整房间", true);
            return;
        }
        String alias = aliasInput.getText().toString().trim();
        if (alias.isEmpty()) {
            aliasInput.setError("请填写房间备注");
            return;
        }
        try {
            String roomId = repository.createRoomId();
            AppConfig config = new AppConfig(
                    alias,
                    selectedRoom.code,
                    "amount",
                    20,
                    25,
                    2_880,
                    Collections.singletonList(new DailyCheckTime(9, 0))
            );
            repository.save(roomId, config);
            // 添加房间只建立本地配置，不擅自开启后台监测；用户可在详情页明确启用。
            repository.setMonitoringEnabled(roomId, false);
            dialog.dismiss();
            listener.onRoomAdded(roomId);
        } catch (IllegalArgumentException exception) {
            setStatus(
                    exception.getMessage() == null ? "房间信息无效" : exception.getMessage(),
                    true
            );
        }
    }

    private void showOptions(
            String title,
            List<DirectoryOption> options,
            OptionListener optionListener
    ) {
        if (options.isEmpty()) return;
        String[] names = new String[options.size()];
        for (int index = 0; index < options.size(); index++) {
            names[index] = options.get(index).name;
        }
        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setItems(names, (ignored, index) -> optionListener.onSelected(options.get(index)))
                .setNegativeButton("取消", null)
                .show();
    }

    private void setBusy(String message) {
        setStatus(message, false);
    }

    private void showFailure(String prefix, Exception exception) {
        String detail = exception instanceof AuthExpiredException
                ? "内置登录态已失效，请更新应用"
                : exception.getMessage() == null ? "未知错误" : exception.getMessage();
        setStatus(prefix + "：" + detail + "，可点击对应项重试", true);
    }

    private void setStatus(String message, boolean error) {
        if (closed || statusText == null) return;
        statusText.setText(message);
        statusText.setTextColor(activity.getColor(
                error ? R.color.status_danger : R.color.text_secondary
        ));
    }

    private void updateAddEnabled() {
        if (dialog != null && dialog.isShowing()) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(selectedRoom != null);
        }
    }

    private void runOnUiThread(Runnable action) {
        activity.runOnUiThread(() -> {
            if (!closed && !activity.isFinishing() && !activity.isDestroyed()) action.run();
        });
    }

    private void close() {
        if (closed) return;
        closed = true;
        executor.shutdownNow();
    }

    private interface OptionListener {
        void onSelected(DirectoryOption option);
    }
}
