package xyz.geik.farmer.integrations.bedrock;

import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.Form;
import org.geysermc.floodgate.api.FloodgateApi;

final class FloodgateFormSender implements BedrockFormSender {
    @Override
    public boolean isBedrockPlayer(Player player) {
        return FloodgateApi.getInstance().isFloodgatePlayer(player.getUniqueId());
    }

    @Override
    public boolean send(Player player, Form form) {
        return FloodgateApi.getInstance().sendForm(player.getUniqueId(), form);
    }
}
