data "aws_iam_policy_document" "ecs_tasks_assume" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

# --- Papel de EXECUÇÃO: o que o ECS usa para iniciar a task (logs e ler os segredos do SSM) ---
resource "aws_iam_role" "execucao" {
  name               = "${var.nome}-execucao"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

resource "aws_iam_role_policy_attachment" "execucao_ecs" {
  role       = aws_iam_role.execucao.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

data "aws_iam_policy_document" "execucao_segredos" {
  statement {
    actions   = ["ssm:GetParameters"]
    resources = [for p in aws_ssm_parameter.segredo : p.arn]
  }
}

resource "aws_iam_role_policy" "execucao_segredos" {
  name   = "ler-segredos"
  role   = aws_iam_role.execucao.id
  policy = data.aws_iam_policy_document.execucao_segredos.json
}

# --- Papel da TASK: o que o auditor (o código) pode fazer. Leitura na conta + escrita SÓ na própria tabela ---
resource "aws_iam_role" "task" {
  name               = "${var.nome}-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

# A policy mínima da Etapa 2, escrita à mão e versionada em docs/iam (um teste confere que ela tem exatamente as ações dos coletores)
resource "aws_iam_policy" "leitura" {
  name   = "${var.nome}-leitura"
  policy = file("${path.module}/../docs/iam/politica-auditor-leitura.json")
}

resource "aws_iam_role_policy_attachment" "task_leitura" {
  role       = aws_iam_role.task.name
  policy_arn = aws_iam_policy.leitura.arn
}

data "aws_iam_policy_document" "task_dynamodb" {
  statement {
    # exatamente o que o RepositorioDeAchados usa (sem DeleteItem, sem criar tabela)
    actions = ["dynamodb:GetItem", "dynamodb:PutItem", "dynamodb:UpdateItem", "dynamodb:Query", "dynamodb:Scan"]
    resources = [
      aws_dynamodb_table.achados.arn,
      "${aws_dynamodb_table.achados.arn}/index/*",
    ]
  }
}

resource "aws_iam_role_policy" "task_dynamodb" {
  name   = "tabela-de-achados"
  role   = aws_iam_role.task.id
  policy = data.aws_iam_policy_document.task_dynamodb.json
}

# --- Papel do AGENDADOR: o EventBridge Scheduler só pode iniciar ESTA task, com ESTES dois papéis ---
data "aws_iam_policy_document" "scheduler_assume" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["scheduler.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "agendador" {
  name               = "${var.nome}-agendador"
  assume_role_policy = data.aws_iam_policy_document.scheduler_assume.json
}

data "aws_iam_policy_document" "agendador" {
  statement {
    actions   = ["ecs:RunTask"]
    resources = [aws_ecs_task_definition.job.arn_without_revision, "${aws_ecs_task_definition.job.arn_without_revision}:*"]

    condition {
      test     = "ArnEquals"
      variable = "ecs:cluster"
      values   = [aws_ecs_cluster.auditor.arn]
    }
  }

  statement {
    actions   = ["iam:PassRole"]
    resources = [aws_iam_role.execucao.arn, aws_iam_role.task.arn]

    condition {
      test     = "StringLike"
      variable = "iam:PassedToService"
      values   = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role_policy" "agendador" {
  name   = "iniciar-a-task"
  role   = aws_iam_role.agendador.id
  policy = data.aws_iam_policy_document.agendador.json
}
