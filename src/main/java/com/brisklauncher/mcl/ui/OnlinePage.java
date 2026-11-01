package com.brisklauncher.mcl.ui;

import com.brisklauncher.mcl.MclContext;
import com.brisklauncher.mcl.i18n.I18n;
import com.brisklauncher.mcl.online.FrpService;
import com.brisklauncher.mcl.online.OnlineState;
import com.brisklauncher.mcl.online.TerracottaService;
import com.brisklauncher.mcl.security.SafeLog;
import java.io.File;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.util.Duration;

public final class OnlinePage extends VBox implements MclPage {
   private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
   private static final Pattern ROOM_CODE = Pattern.compile("U/[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]{4}");
   private final MclContext context;
   private final TextField server = new TextField();
   private final TextField serverPort = new TextField("7000");
   private final TextField token = new TextField();
   private final TextField localPort = new TextField("25565");
   private final TextField remotePort = new TextField("25565");
   private final TextArea logArea = new TextArea();
   private final Button startButton = new Button(I18n.t("online.frp.start"));
   private final Button stopButton = new Button(I18n.t("online.frp.stop"));
   private final Label portStatus = new Label();
   private final Label tcPath = new Label();
   private final Label tcStatus = new Label();
   private final Label tcRoom = new Label();
   private final Label tcAddress = new Label();
   private final Label tcPlayers = new Label();
   private final TextField tcPlayer = new TextField();
   private final TextField tcRoomInput = new TextField();
   private final Button tcHost = new Button(I18n.t("online.p2p.host"));
   private final Button tcGuest = new Button(I18n.t("online.p2p.guest"));
   private final Button tcReset = new Button(I18n.t("online.p2p.reset"));
   private final Button tcStop = new Button(I18n.t("online.p2p.stop"));
   private VBox p2pPane;
   private VBox frpPane;
   private ToggleButton p2pTab;
   private ToggleButton frpTab;
   private Timeline tcPoll;
   private final VBox tcMemberList = new VBox((double)4.0F);
   private long lastMemberRefresh;
   private int roomLatencyMs = -1;
   private volatile boolean polling;
   private boolean terracottaLocked;

   public OnlinePage(MclContext context) {
      this.context = context;
      this.getStyleClass().add("page");
      this.setPadding(new Insets((double)18.0F));
      this.setSpacing((double)12.0F);
      ToggleGroup group = new ToggleGroup();
      group.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
         if (newToggle == null && oldToggle != null) {
            Platform.runLater(() -> oldToggle.setSelected(true));
         }

      });
      this.p2pTab = new ToggleButton(I18n.t("online.tab.p2p"));
      this.p2pTab.setToggleGroup(group);
      this.p2pTab.getStyleClass().add("pill-toggle");
      this.p2pTab.setSelected(true);
      this.p2pTab.setOnAction((e) -> this.show(this.p2pPane));
      this.frpTab = new ToggleButton(I18n.t("online.tab.frp"));
      this.frpTab.setToggleGroup(group);
      this.frpTab.getStyleClass().add("pill-toggle");
      this.frpTab.setOnAction((e) -> this.show(this.frpPane));
      this.p2pPane = this.buildP2P();
      this.frpPane = this.buildFrp();
      HBox tabs = new HBox((double)6.0F, new Node[]{this.p2pTab, this.frpTab});
      this.getChildren().addAll(new Node[]{UiKit.title(I18n.t("online.title")), tabs, this.p2pPane});
   }

   private void show(VBox pane) {
      this.getChildren().set(2, pane);
      if (pane == this.frpPane) {
         this.updatePortStatus();
      }

   }

   public void onShown() {
      this.updatePortStatus();
      if (this.context.frp().isRunning()) {
         this.startButton.setDisable(true);
         this.stopButton.setDisable(false);
      }

      this.refreshTerracottaPath();
      this.startPolling();
   }

   public void onHidden() {
      if (this.tcPoll != null) {
         this.tcPoll.stop();
      }

   }

   private VBox buildP2P() {
      Label attribution = new Label(I18n.t("online.p2p.attribution"));
      attribution.getStyleClass().add("cat-hint");
      attribution.setWrapText(true);
      Label explanation = new Label(I18n.t("online.p2p.intro"));
      explanation.setWrapText(true);
      explanation.getStyleClass().add("instance-meta");
      this.tcPath.getStyleClass().add("cat-hint");
      this.tcPath.setWrapText(true);
      this.tcStatus.getStyleClass().add("instance-meta");
      this.tcStatus.setWrapText(true);
      this.tcRoom.getStyleClass().add("instance-name");
      this.tcAddress.getStyleClass().add("instance-meta");
      this.tcAddress.setWrapText(true);
      this.tcPlayers.getStyleClass().add("cat-hint");
      this.tcPlayers.setWrapText(true);
      Button chooseBinary = new Button(I18n.t("online.p2p.choose"));
      chooseBinary.setOnAction((e) -> this.chooseTerracottaBinary());
      Button openDir = new Button(I18n.t("online.p2p.open_dir"));
      openDir.setOnAction((e) -> {
         try {
            Files.createDirectories(this.context.terracotta().directory());
         } catch (Exception var3) {
         }

         DesktopActions.openFolder(this.context.terracotta().directory());
      });
      Button download = new Button(I18n.t("online.p2p.download"));
      download.setOnAction((e) -> DesktopActions.openUrl("https://github.com/burningtnt/Terracotta/releases"));
      HBox pathRow = new HBox((double)8.0F, new Node[]{this.tcPath, chooseBinary, openDir, download});
      pathRow.setAlignment(Pos.CENTER_LEFT);
      HBox.setHgrow(this.tcPath, Priority.ALWAYS);
      this.tcPlayer.setPromptText(I18n.t("online.p2p.player"));
      this.tcRoomInput.setPromptText("U/XXXX-XXXX-XXXX-XXXX");
      this.tcPlayer.setText((String)this.context.accounts().current().map((account) -> account.name()).orElse("Player"));
      this.tcHost.getStyleClass().add("primary-button");
      this.tcHost.setOnAction((e) -> this.createRoom());
      this.tcGuest.setOnAction((e) -> this.joinRoom());
      this.tcReset.setOnAction((e) -> this.resetRoom());
      this.tcStop.getStyleClass().addAll(new String[]{"button", "window-close"});
      this.tcStop.setOnAction((e) -> this.stopCore());
      Button copyRoom = new Button(I18n.t("online.p2p.copy"));
      copyRoom.setOnAction((e) -> this.copy(this.tcRoom.getText()));
      Button copyAddress = new Button(I18n.t("online.p2p.copy"));
      copyAddress.setOnAction((e) -> this.copy(this.tcAddress.getText()));
      HBox playerRow = new HBox((double)8.0F, new Node[]{new Label(I18n.t("online.p2p.player")), this.tcPlayer, new Label(I18n.t("online.p2p.room_optional")), this.tcRoomInput});
      playerRow.setAlignment(Pos.CENTER_LEFT);
      HBox.setHgrow(this.tcPlayer, Priority.ALWAYS);
      HBox.setHgrow(this.tcRoomInput, Priority.ALWAYS);
      HBox hostRow = new HBox((double)8.0F, new Node[]{this.tcHost, this.tcGuest, this.tcReset, this.tcStop});
      hostRow.setAlignment(Pos.CENTER_LEFT);
      HBox roomRow = new HBox((double)8.0F, new Node[]{this.tcRoom, copyRoom});
      roomRow.setAlignment(Pos.CENTER_LEFT);
      roomRow.visibleProperty().bind(this.tcRoom.textProperty().isNotEmpty());
      roomRow.managedProperty().bind(roomRow.visibleProperty());
      HBox addressRow = new HBox((double)8.0F, new Node[]{this.tcAddress, copyAddress});
      addressRow.setAlignment(Pos.CENTER_LEFT);
      addressRow.visibleProperty().bind(this.tcAddress.textProperty().isNotEmpty());
      addressRow.managedProperty().bind(addressRow.visibleProperty());
      Label hints = UiKit.catHint(I18n.t("online.p2p.hints"));
      hints.setWrapText(true);
      Label interop = UiKit.catHint(I18n.t("online.p2p.interop"));
      interop.setWrapText(true);
      VBox box = new VBox((double)12.0F, new Node[]{UiKit.title(I18n.t("online.p2p.title")), explanation, pathRow, playerRow, hostRow, this.tcStatus, roomRow, addressRow, this.tcPlayers, this.tcMemberList, hints, interop, attribution});
      box.setPadding(new Insets((double)18.0F));
      box.getStyleClass().add("card");
      this.refreshTerracottaPath();
      this.updateTerracottaButtons();
      return box;
   }

   private void refreshTerracottaPath() {
      TerracottaService service = this.context.terracotta();
      service.findBinary().ifPresentOrElse((path) -> this.tcPath.setText(I18n.t("online.p2p.path", path.toString())), () -> this.tcPath.setText(I18n.t("online.p2p.not_found", service.directory().toString())));
   }

   private void chooseTerracottaBinary() {
      FileChooser chooser = new FileChooser();
      chooser.setTitle(I18n.t("online.p2p.choose"));
      chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Terracotta", new String[]{"*.exe", "terracotta"}));
      File file = chooser.showOpenDialog(this.getScene().getWindow());
      if (file != null) {
         this.context.terracotta().setBinary(file.toPath());
         this.refreshTerracottaPath();
         this.updateTerracottaButtons();
      }

   }

   private void startPolling() {
      if (this.tcPoll != null) {
         this.tcPoll.stop();
      }

      this.tcPoll = new Timeline(new KeyFrame[]{new KeyFrame(Duration.seconds((double)1.5F), (e) -> this.pollTerracotta(), new KeyValue[0])});
      this.tcPoll.setCycleCount(-1);
      this.tcPoll.play();
      this.pollTerracotta();
   }

   private void pollTerracotta() {
      if (!this.polling && this.context.terracotta().port() > 0) {
         this.polling = true;
         CompletableFuture.runAsync(() -> {
            try {
               TerracottaService.State state = this.context.terracotta().state();
               Platform.runLater(() -> this.applyState(state));
            } catch (Exception error) {
               Platform.runLater(() -> this.tcStatus.setText(I18n.t("online.p2p.failed", error.getMessage())));
            } finally {
               this.polling = false;
            }

         });
      } else {
         if (this.context.terracotta().port() <= 0 && !this.tcStatus.getText().contains("Terracotta")) {
            this.tcStatus.setText(I18n.t("online.p2p.state", I18n.t("online.p2p.state.waiting")));
         }

      }
   }

   private void createRoom() {
      String player = this.playerName();
      String room = this.tcRoomInput.getText() == null ? "" : this.tcRoomInput.getText().trim();
      if (!room.isEmpty() && !validRoom(room)) {
         MCLDialog.error(this.getScene().getWindow(), I18n.t("online.p2p.host"), I18n.t("online.p2p.invalid_room"));
      } else {
         this.tcStatus.setText(I18n.t("online.p2p.starting"));
         this.updateTerracottaButtons();
         CompletableFuture.runAsync(() -> {
            try {
               this.context.terracotta().host(room.toUpperCase(), player);
            } catch (Exception error) {
               Platform.runLater(() -> {
                  MCLDialog.error(this.getScene().getWindow(), I18n.t("online.p2p.host"), I18n.t("online.p2p.failed", error.getMessage()));
                  this.updateTerracottaButtons();
               });
            }

         });
      }
   }

   private void joinRoom() {
      String room = this.tcRoomInput.getText() == null ? "" : this.tcRoomInput.getText().trim();
      if (room.isEmpty()) {
         MCLDialog.error(this.getScene().getWindow(), I18n.t("online.p2p.guest"), I18n.t("online.p2p.need_room"));
      } else if (!validRoom(room)) {
         MCLDialog.error(this.getScene().getWindow(), I18n.t("online.p2p.guest"), I18n.t("online.p2p.invalid_room"));
      } else {
         String player = this.playerName();
         this.tcStatus.setText(I18n.t("online.p2p.starting"));
         this.updateTerracottaButtons();
         CompletableFuture.runAsync(() -> {
            try {
               this.context.terracotta().guest(room.toUpperCase(), player);
            } catch (Exception error) {
               Platform.runLater(() -> {
                  MCLDialog.error(this.getScene().getWindow(), I18n.t("online.p2p.guest"), I18n.t("online.p2p.failed", error.getMessage()));
                  this.updateTerracottaButtons();
               });
            }

         });
      }
   }

   private void resetRoom() {
      CompletableFuture.runAsync(() -> {
         try {
            this.context.terracotta().setWaiting();
         } catch (Exception var2) {
         }

         Platform.runLater(() -> {
            this.applyState(TerracottaService.State.waiting());
            this.updateTerracottaButtons();
         });
      });
   }

   private void stopCore() {
      CompletableFuture.runAsync(() -> {
         this.context.terracotta().stop();
         Platform.runLater(() -> {
            this.applyState(TerracottaService.State.waiting());
            this.updateTerracottaButtons();
         });
      });
   }

   private void refreshMemberList(TerracottaService.State state) {
      long now = System.currentTimeMillis();
      boolean due = now - this.lastMemberRefresh >= 5000L;
      this.tcPlayers.setText(state.profiles().isEmpty() ? "" : I18n.t("online.p2p.players", String.join("、", state.names())));
      if (due) {
         this.lastMemberRefresh = now;
         String url = state.url();
         Thread.ofVirtual().name("mcl-room-latency").start(() -> {
            int latency = measureRoomLatency(url);
            Platform.runLater(() -> {
               this.roomLatencyMs = latency;
               this.renderMemberRows(state);
            });
         });
      }
   }

   private void renderMemberRows(TerracottaService.State state) {
      this.tcMemberList.getChildren().clear();
      if (state.profiles().isEmpty()) {
         Label empty = UiKit.catHint(I18n.t("online.members.empty"));
         empty.setWrapText(true);
         this.tcMemberList.getChildren().add(empty);
      } else {
         String me = this.playerName();
         String latencyText = this.roomLatencyMs < 0 ? "-" : this.roomLatencyMs + " ms";
         Tooltip tooltip = new Tooltip(I18n.t("online.members.room_latency", this.roomLatencyMs < 0 ? "?" : String.valueOf(this.roomLatencyMs)));

         for(TerracottaService.Profile profile : state.profiles()) {
            String var10002 = profile.name();
            Label name = new Label(var10002 + (profile.name().equalsIgnoreCase(me) ? " " + I18n.t("online.members.you") : ""));
            name.getStyleClass().add("instance-meta");
            Label vendor = new Label(profile.vendor() == null ? "" : profile.vendor());
            vendor.getStyleClass().add("cat-hint");
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            Label latency = new Label(latencyText);
            latency.getStyleClass().add("instance-meta");
            latency.setTooltip(tooltip);
            HBox row = new HBox((double)8.0F, new Node[]{name, vendor, spacer, latency});
            row.setAlignment(Pos.CENTER_LEFT);
            this.tcMemberList.getChildren().add(row);
         }

      }
   }

   private static int measureRoomLatency(String url) {
      if (url != null && !url.isBlank()) {
         String host = url;
         int port = 25565;
         int colon = url.lastIndexOf(58);
         if (colon > 0) {
            host = url.substring(0, colon);

            try {
               port = Integer.parseInt(url.substring(colon + 1).replaceAll("[^0-9]", ""));
            } catch (NumberFormatException var11) {
               port = 25565;
            }
         }

         try {
            Socket socket = new Socket();

            int var7;
            try {
               long start = System.nanoTime();
               socket.connect(new InetSocketAddress(host, port), 1000);
               var7 = (int)Math.max(0L, (System.nanoTime() - start) / 1000000L);
            } catch (Throwable var9) {
               try {
                  socket.close();
               } catch (Throwable var8) {
                  var9.addSuppressed(var8);
               }

               throw var9;
            }

            socket.close();
            return var7;
         } catch (Exception var10) {
            return -1;
         }
      } else {
         return -1;
      }
   }

   private void applyState(TerracottaService.State state) {
      this.tcStatus.setText(I18n.t("online.p2p.state", this.stateText(state)));
      this.tcRoom.setText(state.room().isEmpty() ? "" : I18n.t("online.p2p.room", state.room()));
      this.tcAddress.setText(state.url().isEmpty() ? "" : I18n.t("online.p2p.address", state.url()));
      this.refreshMemberList(state);
      boolean inRoom = state.hostOk() || state.guestOk();
      if (inRoom) {
         String name = this.playerName();
         if (!this.terracottaLocked) {
            this.terracottaLocked = true;
            this.context.onlineState().enter(OnlineState.Phase.IN_ROOM, name);
         }
      } else if (this.terracottaLocked && !this.context.frp().isRunning()) {
         this.terracottaLocked = false;
         this.context.onlineState().leave();
      }

      this.updateTerracottaButtons();
   }

   private String stateText(TerracottaService.State state) {
      String var10000;
      switch (state.state()) {
         case "host-scanning" -> var10000 = I18n.t("online.p2p.state.host_scanning");
         case "host-starting" -> var10000 = I18n.t("online.p2p.state.host_starting");
         case "host-ok" -> var10000 = I18n.t("online.p2p.state.host_ok");
         case "guest-connecting" -> var10000 = I18n.t("online.p2p.state.guest_connecting");
         case "guest-starting" -> var10000 = I18n.t("online.p2p.state.guest_starting");
         case "guest-ok" -> var10000 = I18n.t("online.p2p.state.guest_ok");
         case "exception" -> var10000 = I18n.t("online.p2p.state.exception", this.exceptionText(state.exceptionType()));
         default -> var10000 = I18n.t("online.p2p.state.waiting");
      }

      return var10000;
   }

   private String exceptionText(int type) {
      String var10000;
      switch (type) {
         case 0 -> var10000 = I18n.t("online.p2p.error.0");
         case 1 -> var10000 = I18n.t("online.p2p.error.1");
         case 2 -> var10000 = I18n.t("online.p2p.error.2");
         case 3 -> var10000 = I18n.t("online.p2p.error.3");
         case 4 -> var10000 = I18n.t("online.p2p.error.4");
         case 5 -> var10000 = I18n.t("online.p2p.error.5");
         default -> var10000 = I18n.t("online.p2p.error.unknown");
      }

      return var10000;
   }

   private void updateTerracottaButtons() {
      boolean running = this.context.terracotta().port() > 0;
      boolean available = this.context.terracotta().findBinary().isPresent();
      boolean busy = this.tcStatus.getText().contains(I18n.t("online.p2p.starting"));
      this.tcHost.setDisable(busy || !available);
      this.tcGuest.setDisable(busy || !available);
      this.tcReset.setDisable(!running);
      this.tcStop.setDisable(!running);
   }

   private String playerName() {
      String name = this.tcPlayer.getText() == null ? "" : this.tcPlayer.getText().trim();
      return name.isEmpty() ? "Player" : name;
   }

   private static boolean validRoom(String room) {
      return ROOM_CODE.matcher(room.trim().toUpperCase()).matches();
   }

   private void copy(String text) {
      if (text != null && !text.isEmpty()) {
         int separator = text.indexOf(65306);
         if (separator < 0) {
            separator = text.indexOf(58);
         }

         String value = separator >= 0 && separator + 1 < text.length() ? text.substring(separator + 1).trim() : text.trim();
         ClipboardContent content = new ClipboardContent();
         content.putString(value);
         Clipboard.getSystemClipboard().setContent(content);
         this.tcStatus.setText(I18n.t("online.p2p.copied"));
      }
   }

   private VBox buildFrp() {
      this.server.setPromptText("frps.example.com");
      this.token.setPromptText(I18n.t("online.frp.token"));
      this.serverPort.setPrefColumnCount(6);
      this.localPort.setPrefColumnCount(6);
      this.remotePort.setPrefColumnCount(6);
      this.logArea.setEditable(false);
      this.logArea.setPrefHeight((double)220.0F);
      this.logArea.setStyle("-fx-font-family: Consolas, monospace; -fx-font-size: 12px;");
      VBox.setVgrow(this.logArea, Priority.ALWAYS);
      this.startButton.getStyleClass().add("primary-button");
      this.startButton.setOnAction((e) -> this.startTunnel());
      this.stopButton.setDisable(true);
      this.stopButton.getStyleClass().addAll(new String[]{"button", "window-close"});
      this.stopButton.setOnAction((e) -> this.stopTunnel());
      Button checkPort = new Button(I18n.t("online.frp.check_port"));
      checkPort.setOnAction((e) -> this.updatePortStatus());
      this.portStatus.getStyleClass().add("cat-hint");
      Button openConfig = new Button(I18n.t("online.frp.open_config"));
      openConfig.setOnAction((e) -> this.openConfig());
      HBox portRow = new HBox((double)8.0F, new Node[]{new Label(I18n.t("online.frp.server_port")), this.serverPort, new Label(I18n.t("online.frp.local_port")), this.localPort, new Label(I18n.t("online.frp.remote_port")), this.remotePort, checkPort, openConfig});
      portRow.setAlignment(Pos.CENTER_LEFT);
      HBox actions = new HBox((double)10.0F, new Node[]{this.startButton, this.stopButton});
      actions.setAlignment(Pos.CENTER_LEFT);
      Label hint = UiKit.catHint(I18n.t("online.frp.hint"));
      hint.setWrapText(true);
      VBox box = new VBox((double)12.0F, new Node[]{UiKit.title(I18n.t("online.frp.title")), new Label(I18n.t("online.frp.server")), this.server, portRow, new Label(I18n.t("online.frp.token")), this.token, actions, this.portStatus, new Label(I18n.t("online.frp.logs")), this.logArea, hint});
      box.setPadding(new Insets((double)18.0F));
      box.getStyleClass().add("card");
      return box;
   }

   private void startTunnel() {
      String host = this.server.getText() == null ? "" : this.server.getText().trim();
      if (host.isEmpty()) {
         MCLDialog.error(this.getScene().getWindow(), I18n.t("online.frp.start"), I18n.t("online.frp.need_server"));
      } else {
         int serverPortValue = parseInt(this.serverPort.getText(), 7000);
         int localPortValue = parseInt(this.localPort.getText(), 25565);
         int remotePortValue = parseInt(this.remotePort.getText(), localPortValue);
         FrpService.FrpConfig config = new FrpService.FrpConfig(host, serverPortValue, this.token.getText() == null ? "" : this.token.getText(), localPortValue, remotePortValue);

         try {
            this.context.frp().start(config, this.context.settings().frpPath(), (line) -> Platform.runLater(() -> this.appendLog(line)));
            this.startButton.setDisable(true);
            this.stopButton.setDisable(false);
            this.appendLog(I18n.t("online.frp.log.started", this.context.frp().pid()));
            String account = (String)this.context.accounts().current().map((accountRecord) -> accountRecord.name()).orElse("");
            this.context.onlineState().enter(OnlineState.Phase.IN_ROOM, account);
         } catch (Exception e) {
            SafeLog.warn("启动 FRP 失败");
            MCLDialog.error(this.getScene().getWindow(), I18n.t("online.frp.start"), e.getMessage());
         }

      }
   }

   private void stopTunnel() {
      this.context.frp().stop();
      this.startButton.setDisable(false);
      this.stopButton.setDisable(true);
      this.appendLog(I18n.t("online.frp.log.stopped"));
      this.context.onlineState().leave();
   }

   private void updatePortStatus() {
      int port = parseInt(this.localPort.getText(), 25565);
      boolean open = FrpService.isLocalPortOpen(port);
      this.portStatus.setText(open ? I18n.t("online.frp.port_open", port) : I18n.t("online.frp.port_closed", port));
   }

   private void openConfig() {
      String host = this.server.getText() == null ? "" : this.server.getText().trim();
      int serverPortValue = parseInt(this.serverPort.getText(), 7000);
      int localPortValue = parseInt(this.localPort.getText(), 25565);
      int remotePortValue = parseInt(this.remotePort.getText(), localPortValue);
      FrpService.FrpConfig config = new FrpService.FrpConfig(host, serverPortValue, this.token.getText() == null ? "" : this.token.getText(), localPortValue, remotePortValue);

      try {
         Path file = this.context.frp().writeConfig(config);
         MCLDialog.info(this.getScene().getWindow(), I18n.t("online.frp.open_config"), I18n.t("online.frp.config_saved", file.toString()));
         DesktopActions.openFolder(file.getParent());
      } catch (Exception e) {
         MCLDialog.error(this.getScene().getWindow(), I18n.t("online.frp.open_config"), e.getMessage());
      }

   }

   private void appendLog(String line) {
      TextArea var10000 = this.logArea;
      String var10001 = LocalTime.now().format(TIME);
      var10000.appendText("[" + var10001 + "] " + SafeLog.sanitize(line) + "\n");
   }

   private static int parseInt(String raw, int fallback) {
      try {
         return Integer.parseInt(raw == null ? "" : raw.trim());
      } catch (NumberFormatException var3) {
         return fallback;
      }
   }
}
