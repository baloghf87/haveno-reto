/*
 * This file is part of Haveno.
 *
 * Haveno is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Haveno is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Haveno. If not, see <http://www.gnu.org/licenses/>.
 */

package haveno.orderbook;

import haveno.common.UserThread;
import haveno.common.app.AppModule;
import haveno.core.app.misc.ExecutableForAppWithP2p;
import haveno.core.app.misc.ModuleForAppWithP2p;
import lombok.extern.slf4j.Slf4j;

/**
 * Entry point for the Haveno orderbook observer node. Reuses the lightweight P2P-only
 * bootstrap ({@link ExecutableForAppWithP2p} + {@link ModuleForAppWithP2p}) shared with the
 * seed and statistics nodes, so no Monero wallet is started.
 */
@Slf4j
public class OrderbookMain extends ExecutableForAppWithP2p {
    private static final String VERSION = OrderbookConfig.VERSION;
    private OrderbookNode node;

    public OrderbookMain() {
        super("Haveno Orderbook Node", "haveno-orderbook", "haveno_orderbook", VERSION);
    }

    public static void main(String[] args) {
        log.info("OrderbookMain.VERSION: {}", VERSION);
        new OrderbookMain().execute(args);
    }

    @Override
    protected int doExecute() {
        super.doExecute();
        checkMemory(config, this);
        return keepRunning();
    }

    @Override
    protected void addCapabilities() {
    }

    @Override
    protected void launchApplication() {
        UserThread.execute(() -> {
            try {
                node = new OrderbookNode();
                UserThread.execute(this::onApplicationLaunched);
            } catch (Exception e) {
                log.error("Error launching application", e);
            }
        });
    }

    @Override
    protected void onApplicationLaunched() {
        super.onApplicationLaunched();
    }

    @Override
    protected AppModule getModule() {
        return new ModuleForAppWithP2p(config);
    }

    @Override
    protected void applyInjector() {
        super.applyInjector();
        node.setInjector(injector);
    }

    @Override
    protected void startApplication() {
        node.startApplication();
    }
}
