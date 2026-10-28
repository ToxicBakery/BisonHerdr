package com.toxicbakery.app.bison.common.screen.machine

import androidx.compose.runtime.Composable
import com.toxicbakery.app.bison.resources.Res
import com.toxicbakery.app.bison.resources.needs_user_host_key_changed
import com.toxicbakery.app.bison.resources.needs_user_host_key_unknown
import com.toxicbakery.app.bison.resources.needs_user_key_from_earlier_version
import com.toxicbakery.app.bison.resources.needs_user_key_locked
import com.toxicbakery.app.bison.resources.needs_user_key_locked_no_alias
import com.toxicbakery.app.bison.resources.needs_user_input_cancelled
import com.toxicbakery.app.bison.resources.needs_user_input_required
import com.toxicbakery.app.bison.resources.needs_user_input_timed_out
import com.toxicbakery.app.bison.resources.needs_user_key_missing
import com.toxicbakery.app.bison.resources.needs_user_login_refused
import com.toxicbakery.app.bison.resources.needs_user_other
import com.toxicbakery.app.bison.resources.needs_user_password_missing
import com.toxicbakery.app.bison.resources.needs_user_unreachable
import com.toxicbakery.app.bison.ssh.NeedsUserCause
import org.jetbrains.compose.resources.stringResource

/** Returns the text that tells the user why the connection stopped and what to do. */
@Composable
fun needsUserText(cause: NeedsUserCause): String =
    when (cause) {
        is NeedsUserCause.LoginRefused -> stringResource(Res.string.needs_user_login_refused)
        is NeedsUserCause.HostKeyUnknown -> stringResource(Res.string.needs_user_host_key_unknown, cause.hostPort)
        is NeedsUserCause.HostKeyChanged -> stringResource(Res.string.needs_user_host_key_changed, cause.hostPort)
        is NeedsUserCause.KeyMissing -> stringResource(Res.string.needs_user_key_missing, cause.keyAlias)
        NeedsUserCause.PasswordMissing -> stringResource(Res.string.needs_user_password_missing)
        is NeedsUserCause.UserAuthRequired -> cause.keyAlias
            ?.let { stringResource(Res.string.needs_user_key_locked, it) }
            ?: stringResource(Res.string.needs_user_key_locked_no_alias)
        is NeedsUserCause.KeyFromEarlierVersion ->
            stringResource(Res.string.needs_user_key_from_earlier_version, cause.keyAlias)
        is NeedsUserCause.InputRequired -> stringResource(Res.string.needs_user_input_required, cause.hostPort)
        is NeedsUserCause.InputTimedOut -> stringResource(Res.string.needs_user_input_timed_out, cause.hostPort)
        is NeedsUserCause.InputCancelled -> stringResource(Res.string.needs_user_input_cancelled, cause.hostPort)
        is NeedsUserCause.Unreachable -> stringResource(Res.string.needs_user_unreachable, cause.detail)
        is NeedsUserCause.Other -> stringResource(Res.string.needs_user_other, cause.detail)
    }
