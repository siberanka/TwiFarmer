package xyz.geik.farmer.integrations.bedrock;

import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.Form;

interface BedrockFormSender {
    boolean isBedrockPlayer(Player player);

    boolean send(Player player, Form form);
}
