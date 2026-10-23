package io.github.shaonialife.cardeck.update

import android.content.Context
import org.json.JSONObject

/** 更新线描述独立存放，业务代码不包含仓库地址、分支或发布标签。 */
class UpdateChannel private constructor(
    val repository: String,
    val branch: String,
    val releaseTag: String,
    val apkName: String,
) {
    val releaseUrl get() = "https://api.github.com/repos/$repository/releases/tags/$releaseTag"
    val cachePath get() = "updates/$releaseTag/$apkName"

    fun acceptsSource(tag: String, sourceBranch: String) = tag == releaseTag && sourceBranch == branch

    companion object {
        fun load(context: Context): UpdateChannel {
            val json = context.assets.open("config/update-channel.json").bufferedReader().use { JSONObject(it.readText()) }
            val repository = json.getString("repository")
            val branch = json.getString("branch")
            val releaseTag = json.getString("releaseTag")
            val apkName = json.getString("apkName")
            return create(repository, branch, releaseTag, apkName)
        }

        fun create(repository: String, branch: String, releaseTag: String, apkName: String): UpdateChannel {
            require(repository.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")))
            require(branch.matches(Regex("[A-Za-z0-9_-]+")))
            require(releaseTag.matches(Regex("[A-Za-z0-9_-]+")))
            require(apkName.matches(Regex("[A-Za-z0-9_-]+\\.apk")))
            return UpdateChannel(repository, branch, releaseTag, apkName)
        }
    }
}
