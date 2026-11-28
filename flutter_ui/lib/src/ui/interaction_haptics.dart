import 'package:flutter/services.dart';

/// Haptic punctuation for the two moments this UI can actually prove: a
/// submission its own controller accepted, and the composer really moving into
/// editing one message.
///
/// Flutter's own Android mapping is used on purpose - a light impact reaches
/// `HapticFeedbackConstants.VIRTUAL_KEY` and a selection reaches `CLOCK_TICK` -
/// because it is the path the person's system touch feedback switch already
/// governs. Nothing here reads an in-app motion preference: reduced animation is
/// not a haptics switch. The platform answer never decides whether the business
/// action happened either: a device without a motor, a silenced system switch or
/// a channel error all end at [Future.ignore], which is why a haptic can not
/// turn a send or an edit into a failure.
class InteractionHaptics {
  const InteractionHaptics._();

  /// One light tick right after a send was accepted, a queued one included.
  /// Empty, unsendable, refused, failed, repeated and rebuilt submissions never
  /// reach here.
  static void sendAccepted() => HapticFeedback.lightImpact().ignore();

  /// One selection tick right after the composer really entered editing this
  /// message. A cancelled switch, a re-tap on the row already open, typing and a
  /// rebuild never reach here.
  static void editEntered() => HapticFeedback.selectionClick().ignore();
}
