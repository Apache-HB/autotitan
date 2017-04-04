package extensions

import annotation.Command
import net.dv8tion.jda.core.EmbedBuilder
import net.dv8tion.jda.core.entities.Member
import net.dv8tion.jda.core.entities.Message
import net.dv8tion.jda.core.entities.MessageEmbed
import net.dv8tion.jda.core.entities.User
import net.dv8tion.jda.core.events.message.MessageReceivedEvent

class General {
  @Command()
  fun test(evt: MessageReceivedEvent) {
    evt.channel.sendMessage("Hello World!").queue()
  }
  
  @Command()
  fun memberInfo(evt: MessageReceivedEvent, member: Member) {
    val user = member.user
    val embedBuilder = EmbedBuilder()
    var title = "User info for " + user.name + user.discriminator
    if (member.nickname != null) title += " (also known as " + member.nickname + ")"
    embedBuilder.setTitle(title, null)
    embedBuilder.color = member.color
    embedBuilder.thumbnail = user.effectiveAvatarUrl
  }
}
