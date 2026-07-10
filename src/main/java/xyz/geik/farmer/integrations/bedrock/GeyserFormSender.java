package xyz.geik.farmer.integrations.bedrock;

import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.Form;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.connection.GeyserConnection;

final class GeyserFormSender implements BedrockFormSender {
    @Override
    public boolean isBedrockPlayer(Player player) {
        return connection(player) != null;
    }

    @Override
    public boolean send(Player player, Form form) {
        GeyserConnection connection = connection(player);
        return connection != null && connection.sendForm(form);
    }

    private GeyserConnection connection(Player player) {
        return GeyserApi.api().connectionByUuid(player.getUniqueId());
    }
}
