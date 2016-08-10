package io.github.expansionteam.battleships;

import org.apache.log4j.Logger;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;

import static io.github.expansionteam.battleships.ConnectionThread.*;
import static io.github.expansionteam.battleships.ConnectionThread.GameState.GENERATING_SHIPS;
import static io.github.expansionteam.battleships.ConnectionThread.GameState.TURN_GAME;
import static io.github.expansionteam.battleships.ConnectionThread.Player;
import static io.github.expansionteam.battleships.JsonHandler.*;

class PlayerThread extends Thread {
    private final SocketChannel socketChannel;
    private final static Logger log = Logger.getLogger(PlayerThread.class);
    private final JsonHandler jsonHandler = new JsonHandler();
    private final Player currentPlayer;

    private PlayerThread coupledThread = null;
    private DataInputStream dataInputStream;
    private DataOutputStream dataOutputStream;
    private final ConnectionThread parentThread;

    private CyclicBarrier cyclicBarrier;

    PlayerThread(SocketChannel socketChannel, ConnectionThread parentThread, Player currentPlayer) {
        this.socketChannel = socketChannel;
        this.parentThread = parentThread;
        this.currentPlayer = currentPlayer;
        try {
            dataInputStream = new DataInputStream(socketChannel.socket().getInputStream());
            dataOutputStream = new DataOutputStream(socketChannel.socket().getOutputStream());
        } catch (IOException e) {
            log.trace(e);
        }
    }

    void setCyclicBarrier(CyclicBarrier cyclicBarrier) {
        this.cyclicBarrier = cyclicBarrier;
    }

    void setThreadToInform(PlayerThread playerThread) {
        coupledThread = playerThread;
    }

    private String readFromClient() throws IOException {
        String jsonRequest = dataInputStream.readUTF();
        log.debug("Message Received: " + jsonRequest);
        return jsonRequest;
    }

    private String generateJSONResponse(String jsonRequest, boolean player, RequestState jsonRequestState) {
        return jsonHandler.createMessage(jsonRequest, parentThread.getGameObject(), player, jsonRequestState);
    }

    private void writeToClient(String answer) throws IOException {
        dataOutputStream.writeUTF(answer);
        log.debug("Message Sent: " + answer);
        dataOutputStream.flush();
    }

    @Override
    public void run() {
        try {
            sleep(50);
            RequestState jsonRequestState;
            while (parentThread.getGameState() == GENERATING_SHIPS) {
                String request = readFromClient();
                jsonRequestState = jsonHandler.apply(request, parentThread.getGameObject());
                String answer = generateJSONResponse(request, true, jsonRequestState);
                String type = getJSONType(answer);
                writeToClient(answer);

                if (type.equals("ShipsGeneratedEvent")) {
                    break;
                }

                sleep(100);
            }

            cyclicBarrier.await();

            GameState gameState;

            while ((gameState = parentThread.getGameState()) == TURN_GAME) {
                Player player = gameState.getPlayer();

                if (player == currentPlayer) {
                    String request = readFromClient();
                    jsonRequestState = jsonHandler.apply(request, parentThread.getGameObject());
                    String playerResponse = generateJSONResponse(request, true, jsonRequestState);
                    String opponentResponse = generateJSONResponse(request, false, jsonRequestState);
                    writeToClient(playerResponse);
                    coupledThread.dataOutputStream.writeUTF(opponentResponse);

                    log.info(parentThread.getGameState().getPlayer());
                    log.debug(playerResponse);
                    log.debug(opponentResponse);

                    if (getJSONType(playerResponse).equals("EmptyFieldHitEvent")) {
                        parentThread.getGameState().switchPlayer();
                        coupledThread.interrupt();
                    }

                } else {
                    writeToClient("ready");
                    try {
                        synchronized (this) {
                            wait();
                        }

                    } catch (InterruptedException e) {
                        log.trace(e);
                    }
                }
            }

        } catch (IOException | InterruptedException | BrokenBarrierException e) {
            log.trace(e);
        } finally {
            try {
                socketChannel.close();
            } catch (IOException e) {
                log.trace(e);
            }
        }
    }
}