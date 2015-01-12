package im.tox.gui;

import im.tox.tox4j.ToxCoreImpl;
import im.tox.tox4j.ToxCoreImplTestBase;
import im.tox.tox4j.annotations.NotNull;
import im.tox.tox4j.annotations.Nullable;
import im.tox.tox4j.core.ToxCore;
import im.tox.tox4j.core.ToxOptions;
import im.tox.tox4j.core.callbacks.ToxEventListener;
import im.tox.tox4j.core.enums.*;
import im.tox.tox4j.core.exceptions.ToxBootstrapException;
import im.tox.tox4j.core.exceptions.ToxFriendAddException;
import im.tox.tox4j.core.exceptions.ToxSendMessageException;
import im.tox.tox4j.exceptions.ToxException;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;

import static im.tox.tox4j.ToxCoreTestBase.parseClientId;
import static im.tox.tox4j.ToxCoreTestBase.readableClientId;

public class ToxGui extends JFrame {
    private static final int MAX_MESSAGES = 1000;

    private JList<String> messages;
    private JTabbedPane tabbedPane1;
    private JButton connectButton;
    private JCheckBox enableIPv6CheckBox;
    private JCheckBox enableUDPCheckBox;
    private JRadioButton noneRadioButton;
    private JRadioButton HTTPRadioButton;
    private JRadioButton SOCKSRadioButton;
    private JTextField proxyHost;
    private JTextField bootstrapHost;
    private JTextField bootstrapPort;
    private JTextField bootstrapKey;
    private JButton bootstrapButton;
    private JPanel contentPane;
    private JTextField proxyPort;
    private JTextField friendId;
    private JButton addFriendButton;
    private JTextField friendRequest;
    private JList<Friend> friendList;
    private JTextField messageText;
    private JRadioButton messageRadioButton;
    private JRadioButton actionRadioButton;
    private JButton sendButton;
    private JTextField selfClientId;

    private ToxCore tox;
    private Thread eventLoop;

    private DefaultListModel<String> messageModel = new DefaultListModel<>();
    private FriendList friendListModel = new FriendList();
    private final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("[yyyy-MM-dd HH:mm:ss.SSS]");

    private void addMessage(Object... args) {
        StringBuilder str = new StringBuilder();
        for (Object arg : args) {
            str.append(String.valueOf(arg));
        }
        if (messageModel.size() > MAX_MESSAGES) {
            DefaultListModel<String> newModel = new DefaultListModel<>();
            for (int i = 0; i < messageModel.size() - MAX_MESSAGES / 10; i++) {
                newModel.addElement(messageModel.get(i + MAX_MESSAGES / 10));
            }
            messageModel = newModel;
            messages.setModel(messageModel);
        }
        messageModel.addElement(DATE_FORMAT.format(new Date()) + ' ' + str);
        messages.ensureIndexIsVisible(messageModel.size() - 1);
        save();
    }

    private final ToxEventListener toxEvents = new InvokeLaterToxEventListener(new ToxEventListener() {
        private void addMessage(String method, Object... args) {
            StringBuilder str = new StringBuilder();
            str.append(method);
            str.append('(');
            boolean first = true;
            for (Object arg : args) {
                if (!first) {
                    str.append(", ");
                }
                str.append(arg);
                first = false;
            }
            str.append(')');
            ToxGui.this.addMessage(str.toString());
        }

        @Override
        public void connectionStatus(@NotNull ToxConnection connectionStatus) {
            addMessage("connectionStatus", connectionStatus);
        }

        @Override
        public void fileControl(int friendNumber, int fileNumber, @NotNull ToxFileControl control) {
            addMessage("fileControl", friendNumber, fileNumber, control);
        }

        @Override
        public void fileReceive(int friendNumber, int fileNumber, @NotNull ToxFileKind kind, long fileSize, @NotNull byte[] filename) {
            addMessage("fileReceive", friendNumber, fileNumber, kind, fileSize, new String(filename));
        }

        @Override
        public void fileReceiveChunk(int friendNumber, int fileNumber, long position, @NotNull byte[] data) {
            addMessage("fileReceiveChunk", friendNumber, fileNumber, position, data);
        }

        @Override
        public void fileRequestChunk(int friendNumber, int fileNumber, long position, int length) {
            addMessage("fileRequestChunk", friendNumber, fileNumber, position, length);
        }

        @Override
        public void friendAction(int friendNumber, int timeDelta, @NotNull byte[] message) {
            addMessage("friendAction", friendNumber, timeDelta, new String(message));
        }

        @Override
        public void friendConnectionStatus(int friendNumber, @NotNull ToxConnection connectionStatus) {
            addMessage("friendConnectionStatus", friendNumber, connectionStatus);
            friendListModel.setConnectionStatus(friendNumber, connectionStatus);
        }

        @Override
        public void friendLosslessPacket(int friendNumber, @NotNull byte[] data) {
            addMessage("friendLosslessPacket", friendNumber, readableClientId(data));
        }

        @Override
        public void friendLossyPacket(int friendNumber, @NotNull byte[] data) {
            addMessage("friendLossyPacket", friendNumber, readableClientId(data));
        }

        @Override
        public void friendMessage(int friendNumber, int timeDelta, @NotNull byte[] message) {
            addMessage("friendMessage", friendNumber, timeDelta, new String(message));
        }

        @Override
        public void friendName(int friendNumber, @NotNull byte[] name) {
            addMessage("friendName", friendNumber, new String(name));
            friendListModel.setName(friendNumber, new String(name));
        }

        @Override
        public void friendRequest(@NotNull byte[] clientId, int timeDelta, @NotNull byte[] message) {
            addMessage("friendRequest", readableClientId(clientId), timeDelta, new String(message));
        }

        @Override
        public void friendStatus(int friendNumber, @NotNull ToxStatus status) {
            addMessage("friendStatus", friendNumber, status);
            friendListModel.setStatus(friendNumber, status);
        }

        @Override
        public void friendStatusMessage(int friendNumber, @NotNull byte[] message) {
            addMessage("friendStatusMessage", friendNumber, new String(message));
            friendListModel.setStatusMessage(friendNumber, new String(message));
        }

        @Override
        public void friendTyping(int friendNumber, boolean isTyping) {
            addMessage("friendTyping", friendNumber, isTyping);
            friendListModel.setTyping(friendNumber, isTyping);
        }

        @Override
        public void readReceipt(int friendNumber, int messageId) {
            addMessage("readReceipt", friendNumber, messageId);
        }
    });

    private static final class SaveData implements Serializable {
        public final byte[] toxSave;
        public final FriendList friendList;
        public final DefaultListModel<String> messages;

        private SaveData(byte[] toxSave, FriendList friendList, DefaultListModel<String> messages) {
            this.toxSave = toxSave;
            this.friendList = friendList;
            this.messages = messages;
        }
    }

    private void save() {
        try (ObjectOutputStream saveFile = new ObjectOutputStream(new FileOutputStream("/tmp/toxgui.tox"))) {
            SaveData saveData = new SaveData(tox == null ? null : tox.save(), friendListModel, messageModel);
            saveFile.writeObject(saveData);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private @Nullable byte[] load() {
        try (ObjectInputStream saveFile = new ObjectInputStream(new FileInputStream("/tmp/toxgui.tox"))) {
            SaveData saveData = (SaveData) saveFile.readObject();

            if (saveData.friendList != null) {
                friendListModel = saveData.friendList;
                friendList.setModel(friendListModel);
            }

            if (saveData.messages != null) {
                messageModel = saveData.messages;
                messages.setModel(messageModel);
            }

            return saveData.toxSave;
        } catch (IOException e) {
            return null;
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
            return null;
        }
    }

    public ToxGui() {
        setContentPane(contentPane);
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);

        load();
        messages.ensureIndexIsVisible(messageModel.size() - 1);

        connectButton.addActionListener(new ActionListener() {

            private void setConnectSettingsEnabled(boolean enabled) {
                enableIPv6CheckBox.setEnabled(enabled);
                enableUDPCheckBox.setEnabled(enabled);
                noneRadioButton.setEnabled(enabled);
                HTTPRadioButton.setEnabled(enabled);
                SOCKSRadioButton.setEnabled(enabled);
                proxyHost.setEnabled(enabled);
                proxyPort.setEnabled(enabled);

                bootstrapHost.setEnabled(!enabled);
                bootstrapPort.setEnabled(!enabled);
                bootstrapKey.setEnabled(!enabled);
                bootstrapButton.setEnabled(!enabled);
                friendId.setEnabled(!enabled);
                friendRequest.setEnabled(!enabled);
                addFriendButton.setEnabled(!enabled);

                actionRadioButton.setEnabled(!enabled);
                messageRadioButton.setEnabled(!enabled);
                messageText.setEnabled(!enabled);
                sendButton.setEnabled(!enabled);
            }

            private void connect() {
                try {
                    ToxOptions options = new ToxOptions();
                    options.setIpv6Enabled(enableIPv6CheckBox.isSelected());
                    options.setUdpEnabled(enableUDPCheckBox.isSelected());
                    if (noneRadioButton.isSelected()) {
                        options.disableProxy();
                    } else if (HTTPRadioButton.isSelected()) {
                        options.enableProxy(ToxProxyType.HTTP, proxyHost.getText(), Integer.parseInt(proxyPort.getText()));
                    } else if (SOCKSRadioButton.isSelected()) {
                        options.enableProxy(ToxProxyType.HTTP, proxyHost.getText(), Integer.parseInt(proxyPort.getText()));
                    }

                    byte[] toxSave = load();
                    if (toxSave != null) {
                        tox = new ToxCoreImpl(options, toxSave);
                    } else {
                        tox = new ToxCoreImpl(options);
                    }
                    selfClientId.setText(readableClientId(tox.getAddress()));
                    tox.callback(toxEvents);
                    eventLoop = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            while (true) {
                                tox.iteration();
                                try {
                                    Thread.sleep(tox.iterationInterval());
                                } catch (InterruptedException e) {
                                    return;
                                }
                            }
                        }
                    });
                    eventLoop.start();
                    connectButton.setText("Disconnect");
                    setConnectSettingsEnabled(false);
                    addMessage("Created Tox instance; started event loop");
                } catch (ToxException e) {
                    addMessage("Error creating Tox instance: " + e.getCode());
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(ToxGui.this, e);
                }
            }

            private void disconnect() {
                eventLoop.interrupt();
                try {
                    tox.close();
                    tox = null;
                    eventLoop.join();
                    setConnectSettingsEnabled(true);
                    connectButton.setText("Connect");
                    addMessage("Disconnected");
                } catch (InterruptedException e) {
                    addMessage("Disconnect interrupted");
                }
            }

            @Override
            public void actionPerformed(ActionEvent event) {
                switch (connectButton.getText()) {
                    case "Connect":
                        connect();
                        break;
                    case "Disconnect":
                        disconnect();
                        break;
                }
            }

        });


        bootstrapButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent event) {
                try {
                    tox.bootstrap(bootstrapHost.getText(), Integer.parseInt(bootstrapPort.getText()),
                        parseClientId(bootstrapKey.getText().trim()));
                } catch (ToxBootstrapException e) {
                    addMessage("Bootstrap failed: ", e.getCode());
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(ToxGui.this, e);
                }
            }
        });


        addFriendButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent event) {
                try {
                    int friendNumber;
                    if (friendRequest.getText().isEmpty()) {
                        friendNumber = tox.addFriendNoRequest(parseClientId(friendId.getText()));
                    } else {
                        friendNumber = tox.addFriend(parseClientId(friendId.getText()), friendRequest.getText().getBytes());
                    }
                    friendListModel.add(friendNumber);
                    addMessage("Added friend number " + friendNumber);
                    save();
                } catch (ToxFriendAddException e) {
                    addMessage("Add friend failed: ", e.getCode());
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(ToxGui.this, e);
                }
            }
        });


        sendButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent event) {
                try {
                    int friendNumber = friendList.getSelectedIndex();
                    if (friendNumber == -1) {
                        JOptionPane.showMessageDialog(ToxGui.this, "Select a friend to send a message to");
                    }
                    if (messageRadioButton.isSelected()) {
                        tox.sendMessage(friendNumber, messageText.getText().getBytes());
                        addMessage("Sent message to ", friendNumber + ": " + messageText.getText());
                    } else if (actionRadioButton.isSelected()) {
                        tox.sendAction(friendNumber, messageText.getText().getBytes());
                        addMessage("Sent action to ", friendNumber + ": " + messageText.getText());
                    }
                    messageText.setText("");
                } catch (ToxSendMessageException e) {
                    addMessage("Send message failed: ", e.getCode());
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(ToxGui.this, e);
                }
            }
        });


        messageText.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                if (event.getKeyChar() == '\n') {
                    sendButton.doClick();
                }
            }
        });
    }

}
