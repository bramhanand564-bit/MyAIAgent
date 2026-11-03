import {
  executePhoneAction,
  type PendingPhoneAction,
} from './packs/phoneController';

export interface SkillExecutionResult {
  success: boolean;
  message: string;
}

export async function executeApprovedSkill(
  skillId: string,
  action: PendingPhoneAction,
): Promise<SkillExecutionResult> {
  if (skillId !== 'phone_controller') {
    return { success: false, message: 'This skill handler is not allow-listed.' };
  }
  try {
    return { success: true, message: await executePhoneAction(action) };
  } catch (error) {
    return {
      success: false,
      message: error instanceof Error ? error.message : 'The phone action failed.',
    };
  }
}
